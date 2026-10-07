#pragma once

#include <stdint.h>

#if defined(_WIN32)
#define GPU_API __declspec(dllexport)
#else
#define GPU_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

GPU_API void* gpu_create(int allow_cpu_fallback);
GPU_API int gpu_hardware_accelerated(void* handle);
GPU_API const char* gpu_device_name(void* handle);
GPU_API int gpu_process_rgba8(
    void* handle,
    int operation,
    const uint8_t* input,
    uint8_t* output,
    int width,
    int height,
    float p0,
    float p1,
    float p2,
    float p3);
GPU_API int gpu_blend_rgba8(
    void* handle,
    const uint8_t* base,
    const uint8_t* overlay,
    uint8_t* output,
    int width,
    int height,
    float opacity);
GPU_API int gpu_generate_rgba8(
    void* handle,
    int generator,
    uint8_t* output,
    int width,
    int height,
    int64_t seed,
    float p0,
    float p1,
    float p2,
    float p3);
GPU_API void gpu_destroy(void* handle);
GPU_API const char* gpu_last_error(void);

#ifdef __cplusplus
}
#endif
