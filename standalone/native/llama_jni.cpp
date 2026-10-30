// SPDX-License-Identifier: MIT
// Independent llama.cpp JNI provider. No browser, ChromeML, Dawn or model extraction.
#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
constexpr size_t kMaxOutput = 4u * 1024u * 1024u;
using Clock = std::chrono::steady_clock;

struct DeadlineExceeded : std::runtime_error {
    DeadlineExceeded() : std::runtime_error("model inference exceeded configured deadline") {}
};
struct Model {
    llama_model* model;
    const llama_vocab* vocab;
    uint32_t context_tokens;
    int threads;
    std::mutex inference_mutex;
    Model(llama_model* m, uint32_t c, int t)
        : model(m), vocab(llama_model_get_vocab(m)), context_tokens(c), threads(t) {}
    ~Model() { llama_model_free(model); }
};
std::mutex registry_lock;
std::unordered_map<jlong, std::shared_ptr<Model>> registry;
std::atomic<jlong> next_id{1};
std::once_flag backend_initialized;

void Throw(JNIEnv* env, const char* clazz, const char* message) {
    jclass klass = env->FindClass(clazz);
    if (klass != nullptr) env->ThrowNew(klass, message);
}
void Fail(JNIEnv* env, const char* message) { Throw(env, "java/io/IOException", message); }
std::vector<uint8_t> Bytes(JNIEnv* env, jbyteArray bytes) {
    if (!bytes) throw std::invalid_argument("null UTF-8 input");
    const jsize size = env->GetArrayLength(bytes);
    if (size < 0 || size > 16 * 1024 * 1024)
        throw std::invalid_argument("native input exceeds 16 MiB limit");
    std::vector<uint8_t> result(static_cast<size_t>(size));
    if (size) env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("unable to copy JNI input");
    return result;
}
std::shared_ptr<Model> Lookup(jlong handle) {
    std::lock_guard<std::mutex> lock(registry_lock);
    auto found = registry.find(handle);
    if (found == registry.end()) throw std::runtime_error("native model handle is closed or invalid");
    return found->second;
}
bool DeadlineCallback(void* user) {
    return Clock::now() >= *static_cast<const Clock::time_point*>(user);
}
void CheckDeadline(const Clock::time_point& deadline) {
    if (Clock::now() >= deadline) throw DeadlineExceeded();
}
std::string Generate(Model& state, const std::vector<uint8_t>& input, int max_tokens,
                     const Clock::time_point& deadline) {
    if (input.empty() || input.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max()))
        throw std::invalid_argument("prompt is empty or too long");
    std::lock_guard<std::mutex> guard(state.inference_mutex);
    CheckDeadline(deadline);
    const std::string prompt(input.begin(), input.end());
    if (prompt.find('\0') != std::string::npos) throw std::invalid_argument("prompt has NUL byte");
    const int32_t required = llama_tokenize(state.vocab, prompt.data(),
            static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (required >= 0 || required == std::numeric_limits<int32_t>::min())
        throw std::runtime_error("unable to size prompt tokens");
    const int32_t n_prompt = -required;
    if (n_prompt < 1 || static_cast<uint64_t>(n_prompt) + max_tokens > state.context_tokens)
        throw std::invalid_argument("prompt and requested output exceed configured context");
    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(state.vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                       tokens.data(), n_prompt, true, true) != n_prompt)
        throw std::runtime_error("prompt tokenization failed");

    llama_context_params p = llama_context_default_params();
    p.n_ctx = state.context_tokens;
    p.n_batch = std::min<uint32_t>(state.context_tokens, 512);
    p.n_threads = state.threads;
    p.n_threads_batch = state.threads;
    p.abort_callback = DeadlineCallback;
    p.abort_callback_data = const_cast<Clock::time_point*>(&deadline);
    std::unique_ptr<llama_context, decltype(&llama_free)> ctx(
            llama_init_from_model(state.model, p), &llama_free);
    if (!ctx) throw std::runtime_error("unable to allocate llama.cpp context");
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), &llama_sampler_free);
    if (!sampler) throw std::runtime_error("unable to create token sampler");
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    for (int32_t start = 0; start < n_prompt; ) {
        CheckDeadline(deadline);
        const int32_t count = std::min<int32_t>(n_prompt - start, static_cast<int32_t>(p.n_batch));
        if (llama_decode(ctx.get(), llama_batch_get_one(tokens.data() + start, count)) != 0) {
            CheckDeadline(deadline);
            throw std::runtime_error("prompt decode failed");
        }
        start += count;
    }
    std::string output;
    for (int i = 0; i < max_tokens; ++i) {
        CheckDeadline(deadline);
        llama_token token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
        if (llama_vocab_is_eog(state.vocab, token)) break;
        char piece[512];
        int32_t n = llama_token_to_piece(state.vocab, token, piece, sizeof(piece), 0, true);
        if (n < 0) {
            const size_t required_bytes = static_cast<size_t>(-static_cast<int64_t>(n));
            if (required_bytes > kMaxOutput - output.size())
                throw std::runtime_error("token piece exceeds output limit");
            std::vector<char> larger(required_bytes);
            n = llama_token_to_piece(state.vocab, token, larger.data(),
                                     static_cast<int32_t>(larger.size()), 0, true);
            if (n < 0 || output.size() + static_cast<size_t>(n) > kMaxOutput)
                throw std::runtime_error("token piece exceeds output limit");
            output.append(larger.data(), static_cast<size_t>(n));
        } else {
            if (output.size() + static_cast<size_t>(n) > kMaxOutput)
                throw std::runtime_error("native inference exceeds output limit");
            output.append(piece, static_cast<size_t>(n));
        }
        if (i + 1 < max_tokens) {
            CheckDeadline(deadline);
            if (llama_decode(ctx.get(), llama_batch_get_one(&token, 1)) != 0) {
                CheckDeadline(deadline);
                throw std::runtime_error("generation decode failed");
            }
        }
    }
    CheckDeadline(deadline);
    return output;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_synexia_chromellm_standalone_LlamaCppJniBackend_nOpen(
        JNIEnv* env, jclass, jbyteArray model_path, jint context, jint threads, jint gpu_layers) {
    try {
        if (context < 32 || threads < 1 || gpu_layers < 0)
            throw std::invalid_argument("invalid native model settings");
        const auto bytes = Bytes(env, model_path);
        const std::string path(bytes.begin(), bytes.end());
        if (path.empty() || path.find('\0') != std::string::npos)
            throw std::invalid_argument("invalid model path");
        std::call_once(backend_initialized, [] { llama_backend_init(); });
        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = gpu_layers;
        llama_model* raw = llama_model_load_from_file(path.c_str(), params);
        if (!raw) throw std::runtime_error("llama.cpp could not load the supplied model");
        std::shared_ptr<Model> value(new Model(raw, static_cast<uint32_t>(context), threads));
        if (llama_model_has_encoder(raw) || !llama_model_has_decoder(raw))
            throw std::invalid_argument("this JNI provider currently supports decoder-only models");
        const jlong id = next_id.fetch_add(1);
        if (id <= 0) throw std::runtime_error("native model session IDs exhausted");
        { std::lock_guard<std::mutex> lock(registry_lock); registry.emplace(id, std::move(value)); }
        return id;
    } catch (const std::exception& ex) { if (!env->ExceptionCheck()) Fail(env, ex.what()); return 0; }
      catch (...) { if (!env->ExceptionCheck()) Fail(env, "unclassified native model load failure"); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_synexia_chromellm_standalone_LlamaCppJniBackend_nGenerate(
        JNIEnv* env, jclass, jlong handle, jbyteArray prompt, jint tokens, jlong timeout_ms) {
    try {
        if (tokens < 1 || tokens > 32768 || timeout_ms < 1 || timeout_ms > 3600000)
            throw std::invalid_argument("invalid generation limit or deadline");
        const auto bytes = Bytes(env, prompt);
        const Clock::time_point deadline = Clock::now() + std::chrono::milliseconds(timeout_ms);
        auto state = Lookup(handle);
        const std::string text = Generate(*state, bytes, tokens, deadline);
        jbyteArray result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result != nullptr && !text.empty()) env->SetByteArrayRegion(result, 0,
            static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        return result;
    } catch (const DeadlineExceeded& ex) {
        Throw(env, "java/util/concurrent/TimeoutException", ex.what()); return nullptr;
    } catch (const std::exception& ex) { if (!env->ExceptionCheck()) Fail(env, ex.what()); return nullptr; }
      catch (...) { if (!env->ExceptionCheck()) Fail(env, "unclassified native generation failure"); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_synexia_chromellm_standalone_LlamaCppJniBackend_nClose(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(registry_lock);
    registry.erase(handle); // Shared ownership keeps in-flight inference alive.
}
