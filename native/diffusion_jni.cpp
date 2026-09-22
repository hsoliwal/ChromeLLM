#include "diffusion_native.h"
#include <jni.h>
#include <vector>

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_synexia_chromellm_nativebridge_JniDiffusion_generate(
        JNIEnv* env, jclass, jint width, jint height, jint channels, jint steps, jlong seed,
        jfloat guidance, jfloat eta, jint mode, jfloatArray condition_array,
        jint cw, jint ch, jint cc, jint class_id) {
    std::vector<float> condition;
    if (condition_array) {
        jsize n = env->GetArrayLength(condition_array);
        condition.resize(static_cast<std::size_t>(n));
        env->GetFloatArrayRegion(condition_array, 0, n, condition.data());
    }
    void* handle = diffusion_create(width, height, channels, static_cast<int64_t>(seed));
    if (!handle) {
        jclass ex = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(ex, diffusion_last_error());
        return nullptr;
    }
    std::vector<float> output(static_cast<std::size_t>(width) * height * channels);
    int rc = diffusion_generate(handle, mode, condition.empty() ? nullptr : condition.data(),
        static_cast<int>(condition.size()), cw, ch, cc, class_id, steps, guidance, eta,
        output.data(), static_cast<int>(output.size()));
    diffusion_destroy(handle);
    if (rc != 0) {
        jclass ex = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(ex, diffusion_last_error());
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
}
