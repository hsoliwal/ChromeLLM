#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/photo.hpp>
#include <opencv2/video/tracking.hpp>

namespace {

void throw_state(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) {
        env->ThrowNew(type, message.c_str());
    }
}

bool validate_rgba(JNIEnv* env, jbyteArray data, int width, int height, const char* name) {
    if (!data || width <= 0 || height <= 0) {
        throw_state(env, std::string(name) + " is null or dimensions are invalid");
        return false;
    }
    const jlong expected = static_cast<jlong>(width) * static_cast<jlong>(height) * 4L;
    if (env->GetArrayLength(data) != expected) {
        throw_state(env, std::string(name) + " RGBA length does not match dimensions");
        return false;
    }
    return true;
}

bool validate_mask(JNIEnv* env, jbyteArray data, int width, int height) {
    if (!data || width <= 0 || height <= 0) {
        throw_state(env, "mask is null or dimensions are invalid");
        return false;
    }
    const jlong expected = static_cast<jlong>(width) * static_cast<jlong>(height);
    if (env->GetArrayLength(data) != expected) {
        throw_state(env, "mask length does not match dimensions");
        return false;
    }
    return true;
}

std::vector<std::uint8_t> read_bytes(JNIEnv* env, jbyteArray array) {
    const jsize length = env->GetArrayLength(array);
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
    if (length > 0) {
        env->GetByteArrayRegion(
                array,
                0,
                length,
                reinterpret_cast<jbyte*>(bytes.data()));
    }
    return bytes;
}

jbyteArray write_bytes(JNIEnv* env, const std::uint8_t* data, std::size_t size) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
    if (!result) {
        return nullptr;
    }
    if (size > 0) {
        env->SetByteArrayRegion(
                result,
                0,
                static_cast<jsize>(size),
                reinterpret_cast<const jbyte*>(data));
    }
    return result;
}

jbyteArray write_mat(JNIEnv* env, const cv::Mat& image) {
    cv::Mat continuous = image.isContinuous() ? image : image.clone();
    const std::size_t size = continuous.total() * continuous.elemSize();
    return write_bytes(env, continuous.ptr<std::uint8_t>(), size);
}

cv::Mat rgba_view(std::vector<std::uint8_t>& bytes, int width, int height) {
    return cv::Mat(height, width, CV_8UC4, bytes.data());
}

cv::Mat mask_view(std::vector<std::uint8_t>& bytes, int width, int height) {
    return cv::Mat(height, width, CV_8UC1, bytes.data());
}

cv::Rect clamp_rect(int x, int y, int width, int height, int image_width, int image_height) {
    const int left = std::clamp(x, 0, image_width);
    const int top = std::clamp(y, 0, image_height);
    const int right = std::clamp(x + width, 0, image_width);
    const int bottom = std::clamp(y + height, 0, image_height);
    if (right <= left || bottom <= top) {
        throw std::invalid_argument("rectangle does not intersect image");
    }
    return cv::Rect(left, top, right - left, bottom - top);
}

void restore_alpha(const cv::Mat& source_rgba, cv::Mat& destination_rgba) {
    std::vector<cv::Mat> source_channels;
    std::vector<cv::Mat> destination_channels;
    cv::split(source_rgba, source_channels);
    cv::split(destination_rgba, destination_channels);
    destination_channels[3] = source_channels[3];
    cv::merge(destination_channels, destination_rgba);
}

cv::Mat to_gray(const cv::Mat& rgba) {
    cv::Mat gray;
    cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);
    return gray;
}

cv::Mat optical_flow(const cv::Mat& from_rgba, const cv::Mat& to_rgba) {
    cv::Mat flow;
    cv::calcOpticalFlowFarneback(
            to_gray(from_rgba),
            to_gray(to_rgba),
            flow,
            0.5,
            4,
            17,
            4,
            7,
            1.5,
            cv::OPTFLOW_FARNEBACK_GAUSSIAN);
    return flow;
}

cv::Mat warp_with_flow(const cv::Mat& source, const cv::Mat& flow, float fraction) {
    cv::Mat map_x(source.rows, source.cols, CV_32FC1);
    cv::Mat map_y(source.rows, source.cols, CV_32FC1);

    for (int y = 0; y < source.rows; ++y) {
        const cv::Point2f* flow_row = flow.ptr<cv::Point2f>(y);
        float* x_row = map_x.ptr<float>(y);
        float* y_row = map_y.ptr<float>(y);
        for (int x = 0; x < source.cols; ++x) {
            x_row[x] = static_cast<float>(x) - flow_row[x].x * fraction;
            y_row[x] = static_cast<float>(y) - flow_row[x].y * fraction;
        }
    }

    cv::Mat warped;
    cv::remap(
            source,
            warped,
            map_x,
            map_y,
            cv::INTER_LINEAR,
            cv::BORDER_REFLECT101);
    return warped;
}

} // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_io_synexia_chromellm_vision_nativebridge_OpenCvJni_inpaint(
        JNIEnv* env,
        jclass,
        jbyteArray rgba_array,
        jbyteArray mask_array,
        jint width,
        jint height,
        jfloat radius) {
    if (!validate_rgba(env, rgba_array, width, height, "image") ||
        !validate_mask(env, mask_array, width, height)) {
        return nullptr;
    }

    try {
        std::vector<std::uint8_t> rgba_bytes = read_bytes(env, rgba_array);
        std::vector<std::uint8_t> mask_bytes = read_bytes(env, mask_array);

        cv::Mat rgba = rgba_view(rgba_bytes, width, height);
        cv::Mat mask = mask_view(mask_bytes, width, height);
        cv::threshold(mask, mask, 0, 255, cv::THRESH_BINARY);

        cv::Mat bgr;
        cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);

        cv::Mat repaired;
        cv::inpaint(
                bgr,
                mask,
                repaired,
                std::max(0.1f, static_cast<float>(radius)),
                cv::INPAINT_TELEA);

        cv::Mat out_rgba;
        cv::cvtColor(repaired, out_rgba, cv::COLOR_BGR2RGBA);
        restore_alpha(rgba, out_rgba);
        return write_mat(env, out_rgba);
    } catch (const std::exception& exception) {
        throw_state(env, exception.what());
        return nullptr;
    } catch (const cv::Exception& exception) {
        throw_state(env, exception.what());
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_io_synexia_chromellm_vision_nativebridge_OpenCvJni_seamlessClone(
        JNIEnv* env,
        jclass,
        jbyteArray background_array,
        jint background_width,
        jint background_height,
        jbyteArray object_array,
        jint object_width,
        jint object_height,
        jint center_x,
        jint center_y) {
    if (!validate_rgba(env, background_array, background_width, background_height, "background") ||
        !validate_rgba(env, object_array, object_width, object_height, "object")) {
        return nullptr;
    }

    try {
        std::vector<std::uint8_t> background_bytes = read_bytes(env, background_array);
        std::vector<std::uint8_t> object_bytes = read_bytes(env, object_array);

        cv::Mat background_rgba = rgba_view(background_bytes, background_width, background_height);
        cv::Mat object_rgba = rgba_view(object_bytes, object_width, object_height);

        const int left = center_x - object_width / 2;
        const int top = center_y - object_height / 2;
        const cv::Rect destination_bounds(0, 0, background_width, background_height);
        const cv::Rect requested(left, top, object_width, object_height);
        const cv::Rect visible = requested & destination_bounds;

        if (visible.empty()) {
            return write_mat(env, background_rgba);
        }

        const cv::Rect source_roi(
                visible.x - requested.x,
                visible.y - requested.y,
                visible.width,
                visible.height);

        cv::Mat cropped_rgba = object_rgba(source_roi).clone();

        std::vector<cv::Mat> object_channels;
        cv::split(cropped_rgba, object_channels);
        cv::Mat clone_mask = object_channels[3].clone();
        cv::threshold(clone_mask, clone_mask, 0, 255, cv::THRESH_BINARY);

        if (cv::countNonZero(clone_mask) == 0) {
            return write_mat(env, background_rgba);
        }

        cv::Mat background_bgr;
        cv::Mat object_bgr;
        cv::cvtColor(background_rgba, background_bgr, cv::COLOR_RGBA2BGR);
        cv::cvtColor(cropped_rgba, object_bgr, cv::COLOR_RGBA2BGR);

        const cv::Point clone_center(
                visible.x + visible.width / 2,
                visible.y + visible.height / 2);

        cv::Mat cloned_bgr;
        cv::seamlessClone(
                object_bgr,
                background_bgr,
                clone_mask,
                clone_center,
                cloned_bgr,
                cv::NORMAL_CLONE);

        cv::Mat out_rgba;
        cv::cvtColor(cloned_bgr, out_rgba, cv::COLOR_BGR2RGBA);
        restore_alpha(background_rgba, out_rgba);
        return write_mat(env, out_rgba);
    } catch (const std::exception& exception) {
        throw_state(env, exception.what());
        return nullptr;
    } catch (const cv::Exception& exception) {
        throw_state(env, exception.what());
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_io_synexia_chromellm_vision_nativebridge_OpenCvJni_grabCut(
        JNIEnv* env,
        jclass,
        jbyteArray rgba_array,
        jint width,
        jint height,
        jint x,
        jint y,
        jint rect_width,
        jint rect_height,
        jint iterations) {
    if (!validate_rgba(env, rgba_array, width, height, "image")) {
        return nullptr;
    }

    try {
        std::vector<std::uint8_t> rgba_bytes = read_bytes(env, rgba_array);
        cv::Mat rgba = rgba_view(rgba_bytes, width, height);

        cv::Mat bgr;
        cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);

        cv::Mat labels(height, width, CV_8UC1, cv::Scalar(cv::GC_BGD));
        cv::Mat background_model;
        cv::Mat foreground_model;
        cv::Rect rectangle = clamp_rect(
                x,
                y,
                rect_width,
                rect_height,
                width,
                height);

        cv::grabCut(
                bgr,
                labels,
                rectangle,
                background_model,
                foreground_model,
                std::max(1, static_cast<int>(iterations)),
                cv::GC_INIT_WITH_RECT);

        cv::Mat foreground = (labels == cv::GC_FGD) | (labels == cv::GC_PR_FGD);
        foreground.convertTo(foreground, CV_8UC1, 255.0);
        return write_mat(env, foreground);
    } catch (const std::exception& exception) {
        throw_state(env, exception.what());
        return nullptr;
    } catch (const cv::Exception& exception) {
        throw_state(env, exception.what());
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_io_synexia_chromellm_vision_nativebridge_OpenCvJni_interpolate(
        JNIEnv* env,
        jclass,
        jbyteArray previous_array,
        jbyteArray next_array,
        jint width,
        jint height,
        jfloat position) {
    if (!validate_rgba(env, previous_array, width, height, "previous frame") ||
        !validate_rgba(env, next_array, width, height, "next frame")) {
        return nullptr;
    }

    try {
        const float t = std::clamp(static_cast<float>(position), 0.0f, 1.0f);
        if (t <= 0.0f) {
            std::vector<std::uint8_t> previous = read_bytes(env, previous_array);
            return write_bytes(env, previous.data(), previous.size());
        }
        if (t >= 1.0f) {
            std::vector<std::uint8_t> next = read_bytes(env, next_array);
            return write_bytes(env, next.data(), next.size());
        }

        std::vector<std::uint8_t> previous_bytes = read_bytes(env, previous_array);
        std::vector<std::uint8_t> next_bytes = read_bytes(env, next_array);
        cv::Mat previous = rgba_view(previous_bytes, width, height);
        cv::Mat next = rgba_view(next_bytes, width, height);

        cv::Mat forward = optical_flow(previous, next);
        cv::Mat backward = optical_flow(next, previous);

        cv::Mat warped_previous = warp_with_flow(previous, forward, t);
        cv::Mat warped_next = warp_with_flow(next, backward, 1.0f - t);

        cv::Mat blended;
        cv::addWeighted(
                warped_previous,
                1.0 - static_cast<double>(t),
                warped_next,
                static_cast<double>(t),
                0.0,
                blended);
        return write_mat(env, blended);
    } catch (const std::exception& exception) {
        throw_state(env, exception.what());
        return nullptr;
    } catch (const cv::Exception& exception) {
        throw_state(env, exception.what());
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_io_synexia_chromellm_vision_nativebridge_OpenCvJni_version(
        JNIEnv* env,
        jclass) {
    return env->NewStringUTF(CV_VERSION);
}

} // extern "C"
