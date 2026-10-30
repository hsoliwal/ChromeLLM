// SPDX-License-Identifier: MIT
// Exact llama.cpp checkout required by CMake: see standalone/native/README.md.
#include <jni.h>

#include <chrono>
#include <cstdint>
#include <exception>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#ifndef CHROMELLM_JNI_CONTRACT_FIXTURE
#include "llama.h"
#endif

namespace {
void raise(JNIEnv * env, const char * name, const std::string & message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(name);
    if (cls) env->ThrowNew(cls, message.c_str());
}

std::string bytes(JNIEnv * env, jbyteArray input, const char * field) {
    if (!input) throw std::invalid_argument(std::string(field) + " must not be null");
    const jsize size = env->GetArrayLength(input);
    std::string result(static_cast<std::size_t>(size), '\0');
    if (size) env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("JNI array copy failed");
    return result;
}

jbyteArray outputBytes(JNIEnv * env, const std::string & text) {
    if (text.size() > static_cast<std::size_t>(INT32_MAX)) throw std::length_error("output too large");
    jbyteArray result = env->NewByteArray(static_cast<jsize>(text.size()));
    if (result && !text.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()),
                                reinterpret_cast<const jbyte *>(text.data()));
    }
    return result;
}

bool interrupted(JNIEnv * env) {
    jclass cls = env->FindClass("java/lang/Thread");
    if (!cls) return false;
    jmethodID current = env->GetStaticMethodID(cls, "currentThread", "()Ljava/lang/Thread;");
    jmethodID check = env->GetMethodID(cls, "isInterrupted", "()Z");
    if (!current || !check) {
        env->DeleteLocalRef(cls);
        return false;
    }
    jobject thread = env->CallStaticObjectMethod(cls, current);
    if (!thread || env->ExceptionCheck()) {
        env->DeleteLocalRef(cls);
        return false;
    }
    jboolean flag = env->CallBooleanMethod(thread, check);
    env->DeleteLocalRef(thread);
    env->DeleteLocalRef(cls);
    return flag == JNI_TRUE;
}

#ifndef CHROMELLM_JNI_CONTRACT_FIXTURE
struct State {
    llama_model * model;
    explicit State(llama_model * m) : model(m) { }
    ~State() { llama_model_free(model); }
    State(const State &) = delete;
    State & operator=(const State &) = delete;
};

struct ContextDeleter { void operator()(llama_context * p) const { if (p) llama_free(p); } };
struct SamplerDeleter { void operator()(llama_sampler * p) const { if (p) llama_sampler_free(p); } };
struct BatchDeleter { void operator()(llama_batch_ext * p) const { if (p) llama_batch_ext_free(p); } };

void setBatch(llama_batch_ext * batch, const llama_token * tokens, int32_t count, llama_pos start) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < count; ++i) {
        const int32_t index = llama_batch_ext_add_token(batch, 0, tokens[i]);
        if (index < 0) throw std::runtime_error("native llama batch insertion failed");
        const llama_pos pos = start + i;
        llama_batch_ext_set_pos(batch, index, &pos);
    }
    llama_batch_ext_set_output_logits(batch, count - 1, true);
}

std::string tokenPiece(const llama_vocab * vocab, llama_token token) {
    char local[128];
    int32_t n = llama_token_to_piece(vocab, token, local, sizeof(local), 0, true);
    if (n >= 0) return std::string(local, static_cast<std::size_t>(n));
    const int32_t required = -n;
    if (required <= 0 || required > 8192) throw std::runtime_error("token text exceeds bound");
    std::string large(static_cast<std::size_t>(required), '\0');
    n = llama_token_to_piece(vocab, token, large.data(), required, 0, true);
    if (n < 0) throw std::runtime_error("token conversion failed");
    large.resize(static_cast<std::size_t>(n));
    return large;
}

std::string generate(JNIEnv * env, State * state, const std::string & prompt, int maxTokens,
                     int64_t timeoutMillis, int maxOutputBytes) {
    if (prompt.empty() || maxTokens < 1 || maxTokens > 32768 || timeoutMillis < 1
            || timeoutMillis > 3600000 || maxOutputBytes < 1 || maxOutputBytes > 4194304) {
        throw std::invalid_argument("invalid bounded inference parameters");
    }
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMillis);
    const llama_vocab * vocab = llama_model_get_vocab(state->model);
    int32_t nPrompt = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                                     nullptr, 0, true, true);
    if (nPrompt >= 0 || nPrompt == INT32_MIN) throw std::runtime_error("token count unavailable");
    nPrompt = -nPrompt;
    if (nPrompt < 1 || nPrompt > 16384 || static_cast<int64_t>(nPrompt) + maxTokens > 32768) {
        throw std::invalid_argument("prompt and generation exceed 32768 token context budget");
    }
    std::vector<llama_token> tokens(static_cast<std::size_t>(nPrompt));
    if (llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                       tokens.data(), nPrompt, true, true) != nPrompt) {
        throw std::runtime_error("prompt tokenization failed");
    }
    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(nPrompt + maxTokens);
    params.n_batch = static_cast<uint32_t>(nPrompt);
    std::unique_ptr<llama_context, ContextDeleter> ctx(llama_init_from_model(state->model, params));
    if (!ctx) throw std::runtime_error("failed to allocate llama context");
    std::unique_ptr<llama_batch_ext, BatchDeleter> batch(llama_batch_ext_init(ctx.get()));
    if (!batch) throw std::runtime_error("failed to create llama batch");
    std::unique_ptr<llama_sampler, SamplerDeleter> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()));
    if (!sampler) throw std::runtime_error("failed to allocate sampler");
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    setBatch(batch.get(), tokens.data(), nPrompt, 0);
    int32_t nBatch = nPrompt;
    int32_t position = 0;
    std::string response;
    for (int generated = 0; generated < maxTokens; ++generated) {
        if (interrupted(env)) throw std::runtime_error("CHROMELLM_INTERRUPTED");
        if (std::chrono::steady_clock::now() >= deadline)
            throw std::runtime_error("CHROMELLM_TIMEOUT");
        if (llama_process(ctx.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0)
            throw std::runtime_error("native model evaluation failed");
        position += nBatch;
        if (std::chrono::steady_clock::now() >= deadline)
            throw std::runtime_error("CHROMELLM_TIMEOUT");
        llama_token next = llama_sampler_sample(sampler.get(), ctx.get(), -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        std::string piece = tokenPiece(vocab, next);
        if (response.size() + piece.size() > static_cast<std::size_t>(maxOutputBytes))
            throw std::length_error("native output exceeds 4 MiB");
        response += piece;
        if (generated + 1 < maxTokens) {
            setBatch(batch.get(), &next, 1, position);
            nBatch = 1;
        }
    }
    return response;
}
#else
struct State { std::string path; };
std::string generate(JNIEnv * env, State *, const std::string & prompt,
                     int maxTokens, int64_t timeoutMillis, int maxOutputBytes) {
    if (interrupted(env)) throw std::runtime_error("CHROMELLM_INTERRUPTED");
    if (maxTokens < 1 || timeoutMillis < 1 || maxOutputBytes < 1)
        throw std::invalid_argument("invalid bounds");
    if (prompt == "fixture-timeout") throw std::runtime_error("CHROMELLM_TIMEOUT");
    if (prompt == "fixture-interrupted") throw std::runtime_error("CHROMELLM_INTERRUPTED");
    if (prompt == "fixture-native-error") throw std::runtime_error("fixture synthetic error");
    if (prompt == "fixture-invalid-utf8") return std::string(1, static_cast<char>(0xFF));
    std::string response = "jni-fixture:" + prompt;
    if (response.size() > static_cast<std::size_t>(maxOutputBytes))
        throw std::length_error("fixture output exceeds bound");
    return response;
}
#endif
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_synexia_chromellm_standalone_JniLlamaCppBackend_nativeOpen(
        JNIEnv * env, jclass, jbyteArray modelPath) {
    try {
        std::string path = bytes(env, modelPath, "model path");
        if (path.empty() || path.find('\0') != std::string::npos)
            throw std::invalid_argument("invalid model path");
#ifdef CHROMELLM_JNI_CONTRACT_FIXTURE
        return reinterpret_cast<jlong>(new State{path});
#else
        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = 0; // CPU is mandatory and no hidden accelerator fallback is permitted.
        llama_model * model = llama_model_load_from_file(path.c_str(), params);
        if (!model) throw std::runtime_error("unable to load external GGUF model");
        if (llama_model_has_encoder(model) || llama_model_is_diffusion(model)) {
            llama_model_free(model);
            throw std::runtime_error("this provider supports decoder-only text models");
        }
        return reinterpret_cast<jlong>(new State(model));
#endif
    } catch (const std::exception & e) {
        raise(env, "java/io/IOException", e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_synexia_chromellm_standalone_JniLlamaCppBackend_nativeInfer(
        JNIEnv * env, jclass, jlong handle, jbyteArray promptArray, jint maxTokens,
        jlong timeoutMillis, jint maxOutputBytes) {
    try {
        if (!handle) throw std::invalid_argument("closed native model handle");
        std::string prompt = bytes(env, promptArray, "prompt");
        std::string response = generate(env, reinterpret_cast<State *>(handle), prompt,
                                        maxTokens, timeoutMillis, maxOutputBytes);
        return outputBytes(env, response);
    } catch (const std::exception & e) {
        std::string message(e.what());
        if (message == "CHROMELLM_TIMEOUT")
            raise(env, "java/util/concurrent/TimeoutException", "native inference exceeded deadline");
        else if (message == "CHROMELLM_INTERRUPTED")
            raise(env, "java/lang/InterruptedException", "native inference interrupted");
        else raise(env, "java/io/IOException", message);
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_synexia_chromellm_standalone_JniLlamaCppBackend_nativeClose(
        JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<State *>(handle);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
#ifndef CHROMELLM_JNI_CONTRACT_FIXTURE
    llama_backend_init();
#endif
    return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *, void *) {
#ifndef CHROMELLM_JNI_CONTRACT_FIXTURE
    llama_backend_free();
#endif
}
