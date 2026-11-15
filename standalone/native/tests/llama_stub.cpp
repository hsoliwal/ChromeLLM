// SPDX-License-Identifier: MIT
// A test fixture, NOT an inference engine or compatible substitute for llama.cpp.
#include "llama.h"
#include <chrono>
#include <thread>
#include <algorithm>
#include <cstring>
#include <cstdlib>
#include <string>
struct llama_model {};
struct llama_vocab {};
struct llama_context { llama_context_params p; int sampled = 0; int mode = 2; };
struct llama_sampler {};
struct ggml_backend_reg {};
struct ggml_backend_device { ggml_backend_reg_t registry; };
static ggml_backend_reg cpu_registry, webgpu_registry;
static ggml_backend_device cpu_device{&cpu_registry}, webgpu_device{&webgpu_registry};
static bool HasWebGpu() {
    const char* disabled = std::getenv("CHROMELLM_TEST_NO_WEBGPU");
    return disabled == nullptr || std::strcmp(disabled, "1") != 0;
}
extern "C" {
ggml_backend_reg_t ggml_backend_reg_by_name(const char* name) {
    return HasWebGpu() && std::strcmp(name, "WebGPU") == 0 ? &webgpu_registry : nullptr;
}
size_t ggml_backend_dev_count() { return HasWebGpu() ? 2 : 1; }
ggml_backend_dev_t ggml_backend_dev_get(size_t index) {
    return index == 0 ? &cpu_device : (index == 1 && HasWebGpu() ? &webgpu_device : nullptr);
}
ggml_backend_reg_t ggml_backend_dev_backend_reg(ggml_backend_dev_t device) { return device->registry; }
void llama_backend_init() {}
llama_model_params llama_model_default_params() { return {0, nullptr, LLAMA_SPLIT_MODE_LAYER, -1}; }
llama_model* llama_model_load_from_file(const char* path, llama_model_params params) {
    const std::string name(path);
    if (name.find("rejected") != std::string::npos) return nullptr;
    if (name.find("require-webgpu") != std::string::npos
            && (params.devices == nullptr || params.devices[0] != &webgpu_device
                || params.devices[1] != nullptr || params.n_gpu_layers <= 0
                || params.split_mode != LLAMA_SPLIT_MODE_NONE || params.main_gpu != 0)) return nullptr;
    if (name.find("require-cpu") != std::string::npos
            && (params.devices == nullptr || params.devices[0] != nullptr
                || params.n_gpu_layers != 0)) return nullptr;
    return new llama_model;
}
const llama_vocab* llama_model_get_vocab(const llama_model*) { static llama_vocab v; return &v; }
bool llama_model_has_encoder(const llama_model*) { return false; }
bool llama_model_has_decoder(const llama_model*) { return true; }
void llama_model_free(llama_model* m) { delete m; }
int32_t llama_tokenize(const llama_vocab*, const char* input, int32_t len, llama_token* out, int32_t cap, bool, bool) {
    const std::string prompt(input, static_cast<size_t>(std::max<int32_t>(0, len)));
    if (prompt.rfind("Unicode:", 0) == 0 && prompt != "Unicode: नमस्ते 🦙") return 0;
    if (out == nullptr || cap == 0) return -1;
    if (len == 0 || cap < 1) return -1;
    out[0] = prompt == "Unicode: नमस्ते 🦙" ? 7 :
        (prompt == "invalid-utf8" ? 8 : (prompt == "blank-output" ? 9 : 2));
    return 1;
}
llama_context_params llama_context_default_params() { return {0, 0, 0, 0, nullptr, nullptr}; }
llama_context* llama_init_from_model(llama_model*, llama_context_params p) { return new llama_context{p}; }
void llama_free(llama_context* c) { delete c; }
llama_sampler_chain_params llama_sampler_chain_default_params() { return {}; }
llama_sampler* llama_sampler_chain_init(llama_sampler_chain_params) { return new llama_sampler; }
llama_sampler* llama_sampler_init_greedy() { return new llama_sampler; }
void llama_sampler_chain_add(llama_sampler*, llama_sampler* inner) { delete inner; }
void llama_sampler_free(llama_sampler* s) { delete s; }
llama_batch llama_batch_get_one(llama_token* data, int32_t n) { return {data,n}; }
int32_t llama_decode(llama_context* ctx, llama_batch batch) {
    if (ctx->sampled == 0 && batch.n_tokens && batch.token[0] >= 2) ctx->mode = batch.token[0];
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
    if (ctx->p.abort_callback && ctx->p.abort_callback(ctx->p.abort_callback_data)) return -1;
    return 0;
}
llama_token llama_sampler_sample(llama_sampler*, llama_context* ctx, int32_t) {
    if (ctx->mode == 9) return -1; // blank result
    if (ctx->mode == 8) return ctx->sampled++ == 0 ? 0xff : -1; // invalid UTF-8
    const std::string response = ctx->mode == 7 ? "नमस्ते 🦙" : "OK";
    if (static_cast<size_t>(ctx->sampled) >= response.size()) return -1;
    return static_cast<unsigned char>(response[static_cast<size_t>(ctx->sampled++)]);
}
bool llama_vocab_is_eog(const llama_vocab*, llama_token t) { return t == -1; }
int32_t llama_token_to_piece(const llama_vocab*, llama_token t, char* out, int32_t cap, int32_t, bool) {
    if (cap < 1) return -1;
    out[0] = static_cast<char>(t);
    return 1;
}
}
