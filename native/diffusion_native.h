#pragma once
#include <stdint.h>
#if defined(_WIN32)
#define DIFFUSION_API __declspec(dllexport)
#else
#define DIFFUSION_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif
DIFFUSION_API void* diffusion_create(int width, int height, int channels, int64_t seed);
DIFFUSION_API int diffusion_generate(void* handle, int condition_mode, const float* condition, int condition_length,
    int condition_width, int condition_height, int condition_channels, int class_id, int steps,
    float guidance_scale, float eta, float* output, int output_length);
DIFFUSION_API void diffusion_destroy(void* handle);
DIFFUSION_API const char* diffusion_last_error(void);
#ifdef __cplusplus
}
#endif
