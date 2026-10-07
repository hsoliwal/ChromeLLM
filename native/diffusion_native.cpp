#include "diffusion_native.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <new>
#include <random>
#include <string>
#include <vector>

namespace {
struct Context {
    int width, height, channels;
    std::mt19937_64 rng;
    Context(int w, int h, int c, std::uint64_t seed) : width(w), height(h), channels(c), rng(seed) {}
};
thread_local std::string last_error;

float hash_color(int value, int channel) {
    std::uint32_t h = static_cast<std::uint32_t>(value) * 0x9E3779B9u
                    + static_cast<std::uint32_t>(channel) * 0x7F4A7C15u;
    h ^= h >> 16u;
    return static_cast<float>(h & 0xFFFFu) / 65535.0f;
}

float local_average(const std::vector<float>& data, int width, int height, int channels, int x, int y, int c) {
    float sum = 0.0f; int count = 0;
    for (int dy = -1; dy <= 1; ++dy) {
        int yy = std::clamp(y + dy, 0, height - 1);
        for (int dx = -1; dx <= 1; ++dx) {
            int xx = std::clamp(x + dx, 0, width - 1);
            sum += data[(yy * width + xx) * channels + c]; ++count;
        }
    }
    return sum / static_cast<float>(count);
}

float target(int mode, const float* condition, int length, int cw, int ch, int cc, int class_id,
             int width, int height, int x, int y, int c) {
    if (mode == 0) return 0.0f;
    if (mode == 3) return hash_color(class_id, c);
    if (mode == 4) return length == 0 ? 0.0f : condition[c % length];
    if (!condition || length == 0 || cw <= 0 || ch <= 0) return 0.0f;
    int sx = std::min(cw - 1, x * cw / std::max(width, 1));
    int sy = std::min(ch - 1, y * ch / std::max(height, 1));
    if (mode == 2) {
        float d = condition[sy * cw + sx];
        return d * (0.65f + 0.35f * hash_color(c + 17, c));
    }
    int channels = std::max(cc, 1);
    int sc = std::min(channels - 1, c);
    int idx = (sy * cw + sx) * channels + sc;
    return idx >= 0 && idx < length ? condition[idx] : 0.0f;
}

void normalize(std::vector<float>& v) {
    auto mm = std::minmax_element(v.begin(), v.end());
    float range = std::max(1e-8f, *mm.second - *mm.first);
    for (float& x : v) x = (x - *mm.first) / range;
}
}

extern "C" {
void* diffusion_create(int width, int height, int channels, int64_t seed) {
    last_error.clear();
    if (width <= 0 || height <= 0 || (channels != 1 && channels != 3 && channels != 4)) {
        last_error = "invalid dimensions"; return nullptr;
    }
    try { return new Context(width, height, channels, static_cast<std::uint64_t>(seed)); }
    catch (const std::bad_alloc&) { last_error = "allocation failure"; return nullptr; }
}

int diffusion_generate(void* opaque, int mode, const float* condition, int length, int cw, int ch, int cc, int class_id,
                       int steps, float guidance, float eta, float* output, int output_length) {
    last_error.clear();
    if (!opaque || !output || steps <= 0) { last_error = "invalid argument"; return 1; }
    auto* ctx = static_cast<Context*>(opaque);
    const int size = ctx->width * ctx->height * ctx->channels;
    if (output_length != size) { last_error = "output buffer length mismatch"; return 2; }

    std::normal_distribution<float> normal(0.0f, 1.0f);
    std::vector<float> latent(size), predicted(size), next(size), alpha_bar(steps);
    for (float& x : latent) x = normal(ctx->rng);

    float product = 1.0f;
    for (int i = 0; i < steps; ++i) {
        float t = steps == 1 ? 0.0f : static_cast<float>(i) / static_cast<float>(steps - 1);
        product *= 1.0f - (0.0001f + t * (0.02f - 0.0001f));
        alpha_bar[i] = product;
    }

    for (int step = steps - 1; step >= 0; --step) {
        float progress = steps <= 1 ? 1.0f : 1.0f - static_cast<float>(step) / static_cast<float>(steps - 1);
        for (int y = 0; y < ctx->height; ++y) for (int x = 0; x < ctx->width; ++x) for (int c = 0; c < ctx->channels; ++c) {
            int idx = (y * ctx->width + x) * ctx->channels + c;
            float local = local_average(latent, ctx->width, ctx->height, ctx->channels, x, y, c);
            float structure = latent[idx] - local;
            float t = target(mode, condition, length, cw, ch, cc, class_id, ctx->width, ctx->height, x, y, c);
            float attraction = mode == 0 ? 0.0f : (latent[idx] - t) * std::min(guidance / 10.0f, 2.0f);
            predicted[idx] = structure * (0.55f + 0.35f * progress) + attraction * (0.15f + 0.35f * progress);
        }

        float ab = alpha_bar[step], prev = step == 0 ? 1.0f : alpha_bar[step - 1];
        float sqrt_ab = std::sqrt(ab), sqrt_one = std::sqrt(std::max(1e-12f, 1.0f - ab));
        float sigma = eta * std::sqrt(std::max(0.0f, ((1.0f - prev) / (1.0f - ab)) * (1.0f - ab / prev)));
        float dir = std::sqrt(std::max(0.0f, 1.0f - prev - sigma * sigma));
        for (int i = 0; i < size; ++i) {
            float x0 = (latent[i] - sqrt_one * predicted[i]) / std::max(sqrt_ab, 1e-6f);
            float z = (step == 0 || sigma == 0.0f) ? 0.0f : normal(ctx->rng);
            next[i] = std::sqrt(prev) * x0 + dir * predicted[i] + sigma * z;
        }
        latent.swap(next);
    }
    normalize(latent);
    std::copy(latent.begin(), latent.end(), output);
    return 0;
}

void diffusion_destroy(void* handle) { delete static_cast<Context*>(handle); }
const char* diffusion_last_error(void) { return last_error.c_str(); }
}
