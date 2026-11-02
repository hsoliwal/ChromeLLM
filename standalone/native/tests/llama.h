// SPDX-License-Identifier: MIT
// Test-only llama.cpp subset. Never use as an inference engine or production header.
#pragma once
#include <cstdint>
#include <cstddef>
struct llama_model;
struct llama_context;
struct llama_vocab;
struct llama_sampler;
typedef int32_t llama_token;
typedef bool (*ggml_abort_callback)(void*);
struct llama_model_params { int32_t n_gpu_layers; };
struct llama_context_params {
  uint32_t n_ctx, n_batch;
  int32_t n_threads, n_threads_batch;
  ggml_abort_callback abort_callback;
  void* abort_callback_data;
};
struct llama_sampler_chain_params { bool no_perf; };
struct llama_batch { llama_token* token; int32_t n_tokens; };
extern "C" {
void llama_backend_init(void);
llama_model_params llama_model_default_params(void);
llama_model* llama_model_load_from_file(const char*, llama_model_params);
const llama_vocab* llama_model_get_vocab(const llama_model*);
bool llama_model_has_encoder(const llama_model*);
bool llama_model_has_decoder(const llama_model*);
void llama_model_free(llama_model*);
int32_t llama_tokenize(const llama_vocab*, const char*, int32_t, llama_token*, int32_t, bool, bool);
llama_context_params llama_context_default_params(void);
llama_context* llama_init_from_model(llama_model*, llama_context_params);
void llama_free(llama_context*);
llama_sampler_chain_params llama_sampler_chain_default_params(void);
llama_sampler* llama_sampler_chain_init(llama_sampler_chain_params);
llama_sampler* llama_sampler_init_greedy(void);
void llama_sampler_chain_add(llama_sampler*, llama_sampler*);
void llama_sampler_free(llama_sampler*);
llama_batch llama_batch_get_one(llama_token*, int32_t);
int32_t llama_decode(llama_context*, llama_batch);
llama_token llama_sampler_sample(llama_sampler*, llama_context*, int32_t);
bool llama_vocab_is_eog(const llama_vocab*, llama_token);
int32_t llama_token_to_piece(const llama_vocab*, llama_token, char*, int32_t, int32_t, bool);
}
