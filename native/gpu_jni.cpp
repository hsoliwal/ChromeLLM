#include "gpu_opencl.h"

#include <jni.h>
#include <cstdint>
#include <vector>

namespace {

void throw_state(JNIEnv* env, const char* message) {
    jclass type=env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type,message ? message : "native GPU failure");
}

void* from_handle(jlong handle) {
    return reinterpret_cast<void*>(static_cast<std::intptr_t>(handle));
}

jlong to_handle(void* handle) {
    return static_cast<jlong>(reinterpret_cast<std::intptr_t>(handle));
}

std::vector<std::uint8_t> bytes(JNIEnv* env, jbyteArray array) {
    if (!array) return {};
    jsize length=env->GetArrayLength(array);
    std::vector<std::uint8_t> out(static_cast<std::size_t>(length));
    if (length>0) {
        env->GetByteArrayRegion(array,0,length,reinterpret_cast<jbyte*>(out.data()));
    }
    return out;
}

jbyteArray to_java(JNIEnv* env, const std::vector<std::uint8_t>& data) {
    jbyteArray result=env->NewByteArray(static_cast<jsize>(data.size()));
    if (!result) return nullptr;
    if (!data.empty()) {
        env->SetByteArrayRegion(result,0,static_cast<jsize>(data.size()),
                                reinterpret_cast<const jbyte*>(data.data()));
    }
    return result;
}

bool expected_rgba_size(JNIEnv* env, jbyteArray array, jint width, jint height) {
    if (!array || width<=0 || height<=0) return false;
    jlong expected=static_cast<jlong>(width)*static_cast<jlong>(height)*4L;
    return env->GetArrayLength(array)==expected;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_synexia_chromellm_gpu_nativebridge_JniGpu_create(
        JNIEnv* env,
        jclass,
        jboolean allow_cpu_fallback) {
    void* handle=gpu_create(allow_cpu_fallback ? 1 : 0);
    if (!handle) {
        throw_state(env,gpu_last_error());
        return 0;
    }
    return to_handle(handle);
}

JNIEXPORT jboolean JNICALL
Java_io_synexia_chromellm_gpu_nativebridge_JniGpu_hardwareAccelerated(
        JNIEnv*,
        jclass,
        jlong handle) {
    return gpu_hardware_accelerated(from_handle(handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_io_synexia_chromellm_gpu_nativebridge_JniGpu_deviceName(
        JNIEnv* env,
        jclass,
        jlong handle) {
    return env->NewStringUTF(gpu_device_name(from_handle(handle)));
}

JNIEXPORT jbyteArray JNICALL
Java_io_synexia_chromellm_gpu_nativebridge_JniGpu_process(
        JNIEnv* env,
        jclass,
        jlong handle,
        jint operation,
        jbyteArray input,
        jint width,
        jint height,
        jfloat p0,
        jfloat p1,
        jfloat p2,
        jfloat p3) {
    if (!expected_rgba_size(env,input,width,height)) {
        throw_state(env,"input RGBA buffer length does not match dimensions");
        return nullptr;
    }

    std::vector<std::uint8_t> src=bytes(env,input);
    std::vector<std::uint8_t> dst(src.size());
    int rc=gpu_process_rgba8(
            from_handle(handle),
            operation,
            src.data(),
            dst.data(),
            width,
            height,
            p0,
            p1,
            p2,
            p3);
    if (rc!=0) {
        throw_state(env,gpu_last_error());
        return nullptr;
    }
    return to_java(env,dst);
}

JNIEXPORT jbyteArray JNICALL
Java_io_synexia_chromellm_gpu_nativebridge_JniGpu_blend(
        JNIEnv* env,
        jclass,
        jlong handle,
        jbyteArray base,
        jbyteArray overlay,
        jint width,
        jint height,
        jfloat opacity) {
    if (!expected_rgba_size(env,base,width,height) ||
        !expected_rgba_size(env,overlay,width,height)) {
        throw_state(env,"blend RGBA buffer length does not match dimensions");
        return nullptr;
    }

    std::vector<std::uint8_t> a=bytes(env,base);
    std::vector<std::uint8_t> b=bytes(env,overlay);
    std::vector<std::uint8_t> dst(a.size());
    int rc=gpu_blend_rgba8(
            from_handle(handle),
            a.data(),
            b.data(),
            dst.data(),
            width,
            height,
            opacity);
    if (rc!=0) {
        throw_state(env,gpu_last_error());
        return nullptr;
    }
    return to_java(env,dst);
}

JNIEXPORT jbyteArray JNICALL
Java_io_synexia_chromellm_gpu_nativebridge_JniGpu_generate(
        JNIEnv* env,
        jclass,
        jlong handle,
        jint generator,
        jint width,
        jint height,
        jlong seed,
        jfloat p0,
        jfloat p1,
        jfloat p2,
        jfloat p3) {
    if (width<=0 || height<=0) {
        throw_state(env,"width and height must be positive");
        return nullptr;
    }

    std::size_t size=static_cast<std::size_t>(width)*
                     static_cast<std::size_t>(height)*4u;
    std::vector<std::uint8_t> dst(size);
    int rc=gpu_generate_rgba8(
            from_handle(handle),
            generator,
            dst.data(),
            width,
            height,
            static_cast<std::int64_t>(seed),
            p0,
            p1,
            p2,
            p3);
    if (rc!=0) {
        throw_state(env,gpu_last_error());
        return nullptr;
    }
    return to_java(env,dst);
}

JNIEXPORT void JNICALL
Java_io_synexia_chromellm_gpu_nativebridge_JniGpu_destroy(
        JNIEnv*,
        jclass,
        jlong handle) {
    gpu_destroy(from_handle(handle));
}

} // extern "C"
