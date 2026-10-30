// SPDX-License-Identifier: MIT
#include "llama.h"
#include <chrono>
#include <thread>
#include <algorithm>
#include <cstring>
struct llama_model {};
struct llama_vocab {};
struct llama_context { llama_context_params p; int sampled = 0; };
struct llama_sampler {};
extern "C" {
void llama_backend_init() {}
llama_model_params llama_model_default_params() { return {0}; }
llama_model* llama_model_load_from_file(const char*, llama_model_params) { return new llama_model; }
const llama_vocab* llama_model_get_vocab(const llama_model*) { static llama_vocab v; return &v; }
bool llama_model_has_encoder(const llama_model*) { return false; }
bool llama_model_has_decoder(const llama_model*) { return true; }
void llama_model_free(llama_model* m) { delete m; }
int32_t llama_tokenize(const llama_vocab*, const char*, int32_t len, llama_token* out, int32_t cap, bool, bool) {
    if (out == nullptr || cap == 0) return -1;
    if (len == 0 || cap < 1) return -1;
    out[0] = 2; return 1;
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
int32_t llama_decode(llama_context* ctx, llama_batch) {
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
    if (ctx->p.abort_callback && ctx->p.abort_callback(ctx->p.abort_callback_data)) return -1;
    return 0;
}
llama_token llama_sampler_sample(llama_sampler*, llama_context* ctx, int32_t) {
    if (ctx->sampled++ == 0) return 'O';
    if (ctx->sampled == 2) return 'K';
    return -1;
}
bool llama_vocab_is_eog(const llama_vocab*, llama_token t) { return t == -1; }
int32_t llama_token_to_piece(const llama_vocab*, llama_token t, char* out, int32_t cap, int32_t, bool) {
    if (cap < 1) return -1;
    out[0] = static_cast<char>(t);
    return 1;
}
}
