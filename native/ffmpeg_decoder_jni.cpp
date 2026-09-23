#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/error.h>
#include <libavutil/rational.h>
#include <libswscale/swscale.h>
}

namespace {

struct DecoderContext {
    AVFormatContext* format = nullptr;
    AVCodecContext* codec = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    SwsContext* sws = nullptr;
    int video_stream = -1;
    int width = 0;
    int height = 0;
    double fps = 0.0;
    bool demux_eof = false;
    bool flush_sent = false;
    bool finished = false;

    ~DecoderContext() {
        if (sws) {
            sws_freeContext(sws);
            sws = nullptr;
        }
        if (frame) {
            av_frame_free(&frame);
        }
        if (packet) {
            av_packet_free(&packet);
        }
        if (codec) {
            avcodec_free_context(&codec);
        }
        if (format) {
            avformat_close_input(&format);
        }
    }
};

std::string ffmpeg_error(int code) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(code, buffer, sizeof(buffer));
    return std::string(buffer);
}

void throw_state(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) {
        env->ThrowNew(type, message.c_str());
    }
}

void throw_argument(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    if (type) {
        env->ThrowNew(type, message.c_str());
    }
}

DecoderContext* from_handle(jlong handle) {
    return reinterpret_cast<DecoderContext*>(static_cast<std::intptr_t>(handle));
}

jlong to_handle(DecoderContext* context) {
    return static_cast<jlong>(reinterpret_cast<std::intptr_t>(context));
}

std::string from_java_string(JNIEnv* env, jstring value) {
    if (!value) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

int send_next_packet(DecoderContext& state) {
    while (!state.demux_eof) {
        const int read = av_read_frame(state.format, state.packet);
        if (read < 0) {
            state.demux_eof = true;
            break;
        }

        if (state.packet->stream_index != state.video_stream) {
            av_packet_unref(state.packet);
            continue;
        }

        const int sent = avcodec_send_packet(state.codec, state.packet);
        av_packet_unref(state.packet);

        if (sent == AVERROR(EAGAIN)) {
            return 0;
        }
        if (sent < 0) {
            return sent;
        }
        return 0;
    }

    if (state.demux_eof && !state.flush_sent) {
        const int flushed = avcodec_send_packet(state.codec, nullptr);
        if (flushed < 0 && flushed != AVERROR_EOF && flushed != AVERROR(EAGAIN)) {
            return flushed;
        }
        state.flush_sent = true;
    }

    return 0;
}

int decode_next(DecoderContext& state) {
    if (state.finished) {
        return 0;
    }

    for (;;) {
        const int received = avcodec_receive_frame(state.codec, state.frame);
        if (received == 0) {
            return 1;
        }
        if (received == AVERROR_EOF) {
            state.finished = true;
            return 0;
        }
        if (received != AVERROR(EAGAIN)) {
            return received;
        }

        const int sent = send_next_packet(state);
        if (sent < 0) {
            return sent;
        }

        if (state.demux_eof && state.flush_sent) {
            const int drained = avcodec_receive_frame(state.codec, state.frame);
            if (drained == 0) {
                return 1;
            }
            if (drained == AVERROR_EOF) {
                state.finished = true;
                return 0;
            }
            if (drained != AVERROR(EAGAIN)) {
                return drained;
            }
        }
    }
}

int convert_rgba(DecoderContext& state, std::uint8_t* output, int output_stride) {
    state.sws = sws_getCachedContext(
            state.sws,
            state.frame->width,
            state.frame->height,
            static_cast<AVPixelFormat>(state.frame->format),
            state.width,
            state.height,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            nullptr,
            nullptr,
            nullptr);
    if (!state.sws) {
        return AVERROR(ENOMEM);
    }

    std::uint8_t* destination_data[4] = {output, nullptr, nullptr, nullptr};
    int destination_linesize[4] = {output_stride, 0, 0, 0};

    const int rows = sws_scale(
            state.sws,
            state.frame->data,
            state.frame->linesize,
            0,
            state.frame->height,
            destination_data,
            destination_linesize);
    return rows == state.height ? 0 : AVERROR_EXTERNAL;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_synexia_chromellm_video_nativebridge_NativeFfmpegDecoder_open(
        JNIEnv* env,
        jclass,
        jstring path_value) {
    const std::string path = from_java_string(env, path_value);
    if (path.empty()) {
        throw_argument(env, "input path is empty");
        return 0;
    }

    auto* state = new DecoderContext();

    int rc = avformat_open_input(&state->format, path.c_str(), nullptr, nullptr);
    if (rc < 0) {
        throw_state(env, "avformat_open_input failed: " + ffmpeg_error(rc));
        delete state;
        return 0;
    }

    rc = avformat_find_stream_info(state->format, nullptr);
    if (rc < 0) {
        throw_state(env, "avformat_find_stream_info failed: " + ffmpeg_error(rc));
        delete state;
        return 0;
    }

    const AVCodec* decoder = nullptr;
    rc = av_find_best_stream(
            state->format,
            AVMEDIA_TYPE_VIDEO,
            -1,
            -1,
            &decoder,
            0);
    if (rc < 0 || !decoder) {
        throw_state(env, "no decodable video stream: " + ffmpeg_error(rc));
        delete state;
        return 0;
    }
    state->video_stream = rc;

    AVStream* stream = state->format->streams[state->video_stream];
    state->codec = avcodec_alloc_context3(decoder);
    if (!state->codec) {
        throw_state(env, "avcodec_alloc_context3 failed");
        delete state;
        return 0;
    }

    rc = avcodec_parameters_to_context(state->codec, stream->codecpar);
    if (rc < 0) {
        throw_state(env, "avcodec_parameters_to_context failed: " + ffmpeg_error(rc));
        delete state;
        return 0;
    }

    state->codec->thread_count = 0;
    rc = avcodec_open2(state->codec, decoder, nullptr);
    if (rc < 0) {
        throw_state(env, "avcodec_open2 failed: " + ffmpeg_error(rc));
        delete state;
        return 0;
    }

    state->width = state->codec->width;
    state->height = state->codec->height;
    if (state->width <= 0 || state->height <= 0) {
        throw_state(env, "decoder reported invalid dimensions");
        delete state;
        return 0;
    }

    AVRational guessed = av_guess_frame_rate(state->format, stream, nullptr);
    if (guessed.num <= 0 || guessed.den <= 0) {
        guessed = stream->avg_frame_rate;
    }
    if (guessed.num <= 0 || guessed.den <= 0) {
        guessed = stream->r_frame_rate;
    }
    state->fps = guessed.num > 0 && guessed.den > 0
            ? av_q2d(guessed)
            : 30.0;

    state->packet = av_packet_alloc();
    state->frame = av_frame_alloc();
    if (!state->packet || !state->frame) {
        throw_state(env, "failed to allocate FFmpeg packet/frame");
        delete state;
        return 0;
    }

    return to_handle(state);
}

JNIEXPORT jint JNICALL
Java_io_synexia_chromellm_video_nativebridge_NativeFfmpegDecoder_width(
        JNIEnv* env,
        jclass,
        jlong handle) {
    DecoderContext* state = from_handle(handle);
    if (!state) {
        throw_state(env, "decoder handle is null");
        return 0;
    }
    return state->width;
}

JNIEXPORT jint JNICALL
Java_io_synexia_chromellm_video_nativebridge_NativeFfmpegDecoder_height(
        JNIEnv* env,
        jclass,
        jlong handle) {
    DecoderContext* state = from_handle(handle);
    if (!state) {
        throw_state(env, "decoder handle is null");
        return 0;
    }
    return state->height;
}

JNIEXPORT jdouble JNICALL
Java_io_synexia_chromellm_video_nativebridge_NativeFfmpegDecoder_framesPerSecond(
        JNIEnv* env,
        jclass,
        jlong handle) {
    DecoderContext* state = from_handle(handle);
    if (!state) {
        throw_state(env, "decoder handle is null");
        return 0.0;
    }
    return state->fps;
}

JNIEXPORT jint JNICALL
Java_io_synexia_chromellm_video_nativebridge_NativeFfmpegDecoder_nextRgba(
        JNIEnv* env,
        jclass,
        jlong handle,
        jbyteArray output_array) {
    DecoderContext* state = from_handle(handle);
    if (!state || !output_array) {
        throw_state(env, "decoder handle/output is null");
        return -1;
    }

    const jlong expected =
            static_cast<jlong>(state->width) *
            static_cast<jlong>(state->height) * 4L;
    if (env->GetArrayLength(output_array) != expected) {
        throw_argument(env, "output RGBA buffer length does not match decoder dimensions");
        return -1;
    }

    const int decoded = decode_next(*state);
    if (decoded <= 0) {
        if (decoded < 0) {
            throw_state(env, "decode failed: " + ffmpeg_error(decoded));
        }
        return decoded;
    }

    std::vector<std::uint8_t> rgba(static_cast<std::size_t>(expected));
    const int converted = convert_rgba(*state, rgba.data(), state->width * 4);
    av_frame_unref(state->frame);

    if (converted < 0) {
        throw_state(env, "sws_scale failed: " + ffmpeg_error(converted));
        return converted;
    }

    env->SetByteArrayRegion(
            output_array,
            0,
            static_cast<jsize>(rgba.size()),
            reinterpret_cast<const jbyte*>(rgba.data()));
    return 1;
}

JNIEXPORT jint JNICALL
Java_io_synexia_chromellm_video_nativebridge_NativeFfmpegDecoder_seekMillis(
        JNIEnv* env,
        jclass,
        jlong handle,
        jlong millis) {
    DecoderContext* state = from_handle(handle);
    if (!state) {
        throw_state(env, "decoder handle is null");
        return -1;
    }

    AVStream* stream = state->format->streams[state->video_stream];
    const AVRational milliseconds = {1, 1000};
    const std::int64_t timestamp = av_rescale_q(
            static_cast<std::int64_t>(millis),
            milliseconds,
            stream->time_base);

    const int rc = av_seek_frame(
            state->format,
            state->video_stream,
            timestamp,
            AVSEEK_FLAG_BACKWARD);
    if (rc < 0) {
        throw_state(env, "av_seek_frame failed: " + ffmpeg_error(rc));
        return rc;
    }

    avcodec_flush_buffers(state->codec);
    av_packet_unref(state->packet);
    av_frame_unref(state->frame);
    state->demux_eof = false;
    state->flush_sent = false;
    state->finished = false;
    return 0;
}

JNIEXPORT jstring JNICALL
Java_io_synexia_chromellm_video_nativebridge_NativeFfmpegDecoder_version(
        JNIEnv* env,
        jclass) {
    return env->NewStringUTF(av_version_info());
}

JNIEXPORT void JNICALL
Java_io_synexia_chromellm_video_nativebridge_NativeFfmpegDecoder_close(
        JNIEnv*,
        jclass,
        jlong handle) {
    delete from_handle(handle);
}

} // extern "C"
