// SPDX-License-Identifier: MIT
// Agent fixture additions: Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project; Apache-2.0.
// A test fixture, NOT an inference engine or compatible substitute for llama.cpp.
#include "llama.h"
#include <chrono>
#include <thread>
#include <algorithm>
#include <cstring>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <limits>
#include <string>
struct ggml_backend_reg { const char* name; };
struct ggml_backend_device {
    ggml_backend_reg_t backend;
    const char* name;
    const char* description;
    enum ggml_backend_dev_type type;
};
// These are synthetic inventory entries. They never create or execute a GPU device.
static ggml_backend_reg cpu{"CPU"}, webgpu{"WebGPU"}, vulkan{"Vulkan"}, blas{"BLAS"}, duplicate{"Duplicate"};
static ggml_backend_device devices[]{
    {&cpu, "CPU", "synthetic CPU", GGML_BACKEND_DEVICE_TYPE_CPU},
    {&webgpu, "WebGPU", "synthetic WebGPU, not Dawn execution", GGML_BACKEND_DEVICE_TYPE_GPU},
    {&vulkan, "Vulkan0", "synthetic integrated GPU नमस्ते", GGML_BACKEND_DEVICE_TYPE_IGPU},
    {&blas, "BLAS", "synthetic CPU accelerator", GGML_BACKEND_DEVICE_TYPE_ACCEL},
    {&duplicate, "same", "synthetic ambiguous device one", GGML_BACKEND_DEVICE_TYPE_GPU},
    {&duplicate, "same", "synthetic ambiguous device two", GGML_BACKEND_DEVICE_TYPE_GPU}
};
struct llama_model { llama_model_params params; std::string path; };
struct llama_vocab {};
struct llama_context { llama_context_params p; std::string path; int sampled = 0; int mode = 2; };
struct llama_sampler {};
static bool HasWebGpu() {
    const char* disabled = std::getenv("CHROMELLM_TEST_NO_WEBGPU");
    return disabled == nullptr || std::strcmp(disabled, "1") != 0;
}
extern "C" {
ggml_backend_reg_t ggml_backend_reg_by_name(const char* name) {
    return HasWebGpu() && std::strcmp(name, "WebGPU") == 0 ? &webgpu : nullptr;
}
void llama_backend_init() {}
// Without the WebGPU provider the registry simply has no entry at the WebGPU position.
size_t ggml_backend_dev_count() { return sizeof(devices) / sizeof(devices[0]) - (HasWebGpu() ? 0 : 1); }
ggml_backend_dev_t ggml_backend_dev_get(size_t index) {
    if (index >= ggml_backend_dev_count()) return nullptr;
    return &devices[!HasWebGpu() && index >= 1 ? index + 1 : index];
}
const char* ggml_backend_dev_name(ggml_backend_dev_t device) { return device->name; }
const char* ggml_backend_dev_description(ggml_backend_dev_t device) { return device->description; }
enum ggml_backend_dev_type ggml_backend_dev_type(ggml_backend_dev_t device) { return device->type; }
ggml_backend_reg_t ggml_backend_dev_backend_reg(ggml_backend_dev_t device) { return device->backend; }
const char* ggml_backend_reg_name(ggml_backend_reg_t backend) { return backend->name; }
llama_model_params llama_model_default_params() { return {nullptr, 0, LLAMA_SPLIT_MODE_LAYER, 7}; }
static bool correctSelection(const char* path, const llama_model_params& p) {
    const std::string name(path);
    if (name.find("require-webgpu") != std::string::npos) {
        return p.devices != nullptr && p.devices[0] == &devices[1] && p.devices[1] == nullptr
            && p.n_gpu_layers == 3 && p.split_mode == LLAMA_SPLIT_MODE_NONE && p.main_gpu == 0;
    }
    if (name.find("require-cpu") != std::string::npos) {
        return p.devices != nullptr && p.devices[0] == nullptr && p.n_gpu_layers == 0
            && p.split_mode == LLAMA_SPLIT_MODE_NONE && p.main_gpu == -1;
    }
    if (name.find("require-default") != std::string::npos) {
        return p.devices == nullptr && p.n_gpu_layers == 0
            && p.split_mode == LLAMA_SPLIT_MODE_LAYER && p.main_gpu == 7;
    }
    return true;
}
llama_model* llama_model_load_from_file(const char* path, llama_model_params params) {
    if (std::string(path).find("rejected") != std::string::npos) return nullptr;
    if (!correctSelection(path, params)) return nullptr;
    return new llama_model{params, path};
}
const llama_vocab* llama_model_get_vocab(const llama_model* model) {
    static llama_vocab v;
    return model->path.find("null-vocabulary") == std::string::npos ? &v : nullptr;
}
bool llama_model_has_encoder(const llama_model*) { return false; }
bool llama_model_has_decoder(const llama_model*) { return true; }
void llama_model_free(llama_model* m) {
    if (m->path.find("slow-free") != std::string::npos) {
        { std::ofstream marker(std::filesystem::u8path(m->path + ".free-ready"), std::ios::binary); marker << "ready\n"; }
        std::this_thread::sleep_for(std::chrono::milliseconds(1500));
    }
    delete m;
}
int32_t llama_tokenize(const llama_vocab*, const char* input, int32_t len, llama_token* out, int32_t cap, bool, bool) {
    const std::string prompt(input, static_cast<size_t>(std::max<int32_t>(0, len)));
    if (prompt.rfind("Unicode:", 0) == 0 && prompt != "Unicode: नमस्ते 🦙") return 0;
    if (out == nullptr || cap == 0) return -1;
    if (len == 0 || cap < 1) return -1;
    // Scripted agent protocol fixture. This exercises JNI bytes, never model quality.
    if (prompt.find("fixture-agent-contract") != std::string::npos) {
        out[0] = prompt.find("fixture-observation-ready") == std::string::npos ? 18 : 19;
        return 1;
    }
    // Scripted protocol output tests the actual JNI boundary, not model reasoning.
    if (prompt.find("CHROMELLM_AGENT_FIXTURE") != std::string::npos) {
        out[0] = prompt.find("agent-jni-observation") == std::string::npos ? 16 : 17;
        return 1;
    }
    out[0] = prompt == "Unicode: नमस्ते 🦙" ? 7 :
        (prompt == "invalid-utf8" ? 8 : (prompt == "blank-output" ? 9 :
        (prompt == "wait-interrupt" ? 10 : (prompt == "foreign-interrupt" ? 11 :
        (prompt == "oversized-piece" ? 12 : (prompt == "oversized-retry-piece" ? 13 :
        (prompt == "large-valid-piece" ? 14 : (prompt == "minimum-piece-size" ? 15 : 2))))))));
    return 1;
}
llama_context_params llama_context_default_params() { return {0, 0, 0, 0, nullptr, nullptr}; }
llama_context* llama_init_from_model(llama_model* model, llama_context_params p) {
    // Re-read the stored device pointer after model load: selection storage must outlive loading.
    return correctSelection(model->path.c_str(), model->params) ? new llama_context{p, model->path} : nullptr;
}
void llama_free(llama_context* c) { delete c; }
llama_sampler_chain_params llama_sampler_chain_default_params() { return {}; }
llama_sampler* llama_sampler_chain_init(llama_sampler_chain_params) { return new llama_sampler; }
llama_sampler* llama_sampler_init_greedy() { return new llama_sampler; }
void llama_sampler_chain_add(llama_sampler*, llama_sampler* inner) { delete inner; }
void llama_sampler_free(llama_sampler* s) { delete s; }
llama_batch llama_batch_get_one(llama_token* data, int32_t n) { return {data,n}; }
int32_t llama_decode(llama_context* ctx, llama_batch batch) {
    if (ctx->sampled == 0 && batch.n_tokens && batch.token[0] >= 2) ctx->mode = batch.token[0];
    if (ctx->sampled == 0 && (ctx->mode == 10 || ctx->mode == 11)) {
        { std::ofstream marker(std::filesystem::u8path(ctx->path + ".ready"), std::ios::binary); marker << "ready\n"; }
        int result = 0;
        auto wait_for_abort = [&] {
            for (int i = 0; i < 400; ++i) {
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
                if (ctx->p.abort_callback && ctx->p.abort_callback(ctx->p.abort_callback_data)) {
                    result = -1;
                    return;
                }
            }
        };
        if (ctx->mode == 11) { std::thread foreign(wait_for_abort); foreign.join(); }
        else wait_for_abort();
        if (result == 0) {
            std::ofstream marker(std::filesystem::u8path(ctx->path + ".completed"), std::ios::binary); marker << "completed\n";
        }
        return result;
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
    if (ctx->p.abort_callback && ctx->p.abort_callback(ctx->p.abort_callback_data)) return -1;
    return 0;
}
llama_token llama_sampler_sample(llama_sampler*, llama_context* ctx, int32_t) {
    if (ctx->mode == 9) return -1; // blank result
    if (ctx->mode == 8) return ctx->sampled++ == 0 ? 0xff : -1; // invalid UTF-8
    if (ctx->mode >= 12 && ctx->mode <= 15) return ctx->sampled++ == 0 ? -100 - ctx->mode : -1;
    const std::string response = ctx->mode == 18
        ? "{\"type\":\"tool\",\"name\":\"fixture_status\",\"arguments\":{}}"
        : ctx->mode == 19 ? "{\"type\":\"final\",\"answer\":\"native agent fixture completed\"}"
        : ctx->mode == 16 ? R"({"type":"plan","steps":[{"tool":"workspace.read","arguments":{"path":"source.txt","maxLines":2}}]})"
        : ctx->mode == 17 ? R"({"type":"final","text":"JNI agent result नमस्ते"})"
        : ctx->mode == 7 ? "नमस्ते 🦙" : "OK";
    if (static_cast<size_t>(ctx->sampled) >= response.size()) return -1;
    return static_cast<unsigned char>(response[static_cast<size_t>(ctx->sampled++)]);
}
bool llama_vocab_is_eog(const llama_vocab*, llama_token t) { return t == -1; }
int32_t llama_token_to_piece(const llama_vocab*, llama_token t, char* out, int32_t cap, int32_t, bool) {
    if (t == -112) { std::memset(out, 'A', static_cast<size_t>(cap)); return cap + 1; }
    if (t == -113 || t == -114) {
        if (cap < 600) return -600;
        std::memset(out, 'A', 600);
        return t == -113 ? 601 : 600;
    }
    if (t == -115) return std::numeric_limits<int32_t>::min();
    if (cap < 1) return -1;
    out[0] = static_cast<char>(t);
    return 1;
}
}
