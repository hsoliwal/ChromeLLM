// SPDX-License-Identifier: MIT
// Independent llama.cpp JNI provider. Uses only backends registered by the supplied llama.cpp build.
#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
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
struct DeviceSelection {
    std::string backend, name, description, type;
    // llama_model retains a copy of its parameters, so this list lives with the model.
    std::array<ggml_backend_dev_t, 2> offload_devices{{nullptr, nullptr}};
};
struct Model {
    llama_model* model;
    const llama_vocab* vocab;
    uint32_t context_tokens;
    int threads;
    std::shared_ptr<DeviceSelection> selection;
    std::mutex inference_mutex;
    Model(llama_model* m, uint32_t c, int t, std::shared_ptr<DeviceSelection> selected)
        : model(m), vocab(llama_model_get_vocab(m)), context_tokens(c), threads(t),
          selection(std::move(selected)) {}
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
std::string DeviceText(const char* value) {
    if (value == nullptr) throw std::runtime_error("missing native device metadata");
    const size_t length = std::strlen(value);
    if (length > 65536) throw std::runtime_error("native device metadata exceeds limit");
    return std::string(value, length);
}
DeviceSelection DescribeDevice(ggml_backend_dev_t device) {
    if (device == nullptr) throw std::runtime_error("invalid registered device");
    ggml_backend_reg_t backend = ggml_backend_dev_backend_reg(device);
    if (backend == nullptr) throw std::runtime_error("device has no registered backend");
    DeviceSelection result;
    result.backend = DeviceText(ggml_backend_reg_name(backend));
    result.name = DeviceText(ggml_backend_dev_name(device));
    result.description = DeviceText(ggml_backend_dev_description(device));
    switch (ggml_backend_dev_type(device)) {
        case GGML_BACKEND_DEVICE_TYPE_CPU: result.type = "CPU"; break;
        case GGML_BACKEND_DEVICE_TYPE_GPU: result.type = "GPU"; break;
        case GGML_BACKEND_DEVICE_TYPE_IGPU: result.type = "IGPU"; break;
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: result.type = "ACCEL"; break;
        default: throw std::runtime_error("unknown native device type");
    }
    return result;
}
std::string Selector(JNIEnv* env, jbyteArray field) {
    const auto bytes = Bytes(env, field);
    if (bytes.empty() || bytes.size() > 4096) throw std::invalid_argument("invalid device selector length");
    std::string value(bytes.begin(), bytes.end());
    if (std::any_of(value.begin(), value.end(), [](unsigned char c) { return c < 0x20 || c == 0x7f; }))
        throw std::invalid_argument("control character in device selector");
    return value;
}
size_t DeviceCount() {
    const size_t count = ggml_backend_dev_count();
    if (count > 1024) throw std::runtime_error("native device inventory exceeds limit");
    return count;
}
std::shared_ptr<DeviceSelection> SelectDevice(
        const std::string& backend, const std::string& name, int gpu_layers) {
    std::shared_ptr<DeviceSelection> selected;
    const size_t count = DeviceCount();
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t device = ggml_backend_dev_get(i);
        DeviceSelection candidate = DescribeDevice(device);
        if (candidate.backend != backend || candidate.name != name) continue;
        if (selected) throw std::invalid_argument("backend/device selector is ambiguous");
        if (candidate.type == "ACCEL")
            throw std::invalid_argument("accelerator is not a selectable model offload device");
        if (candidate.type == "CPU") {
            if (gpu_layers != 0) throw std::invalid_argument("explicit CPU device requires zero GPU layers");
        } else {
            if (gpu_layers <= 0) throw std::invalid_argument("explicit GPU device requires positive GPU layers");
            candidate.offload_devices[0] = device;
        }
        selected = std::make_shared<DeviceSelection>(std::move(candidate));
    }
    if (!selected) throw std::invalid_argument("requested backend/device is not registered by this llama.cpp build");
    return selected;
}
jobjectArray DeviceFields(JNIEnv* env, const std::vector<DeviceSelection>& devices) {
    if (devices.size() > 1024) throw std::runtime_error("native device inventory exceeds limit");
    jclass byte_array = env->FindClass("[B");
    if (byte_array == nullptr) return nullptr;
    jobjectArray fields = env->NewObjectArray(static_cast<jsize>(devices.size() * 4), byte_array, nullptr);
    env->DeleteLocalRef(byte_array);
    if (fields == nullptr) return nullptr;
    jsize index = 0;
    for (const auto& device : devices) {
        for (const std::string* value : {&device.backend, &device.name, &device.description, &device.type}) {
            jbyteArray field = env->NewByteArray(static_cast<jsize>(value->size()));
            if (field == nullptr) return nullptr;
            if (!value->empty()) env->SetByteArrayRegion(field, 0, static_cast<jsize>(value->size()),
                    reinterpret_cast<const jbyte*>(value->data()));
            if (!env->ExceptionCheck()) env->SetObjectArrayElement(fields, index++, field);
            env->DeleteLocalRef(field);
            if (env->ExceptionCheck()) return nullptr;
        }
    }
    return fields;
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
    llama_sampler* greedy = llama_sampler_init_greedy();
    if (!greedy) throw std::runtime_error("unable to allocate greedy sampler");
    llama_sampler_chain_add(sampler.get(), greedy);
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
jlong OpenModel(JNIEnv* env, jbyteArray model_path, jint context, jint threads, jint gpu_layers,
                std::shared_ptr<DeviceSelection> selection = nullptr) {
        if (context < 32 || context > 1'048'576 || threads < 1 || threads > 1024
                || gpu_layers < 0 || gpu_layers > 1024)
            throw std::invalid_argument("invalid native model settings");
        const auto bytes = Bytes(env, model_path);
        const std::string path(bytes.begin(), bytes.end());
        if (path.empty() || path.find('\0') != std::string::npos)
            throw std::invalid_argument("invalid model path");
        std::call_once(backend_initialized, [] { llama_backend_init(); });
        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = gpu_layers;
        if (selection) {
            params.devices = selection->offload_devices.data();
            params.split_mode = LLAMA_SPLIT_MODE_NONE;
            params.main_gpu = 0;
        }
        // Own the model even when validation or shared_ptr allocation throws.
        std::unique_ptr<llama_model, decltype(&llama_model_free)> raw(
                llama_model_load_from_file(path.c_str(), params), &llama_model_free);
        if (!raw) throw std::runtime_error("llama.cpp could not load the supplied model");
        if (llama_model_has_encoder(raw.get()) || !llama_model_has_decoder(raw.get()))
            throw std::invalid_argument("this JNI provider currently supports decoder-only models");
        std::shared_ptr<Model> value = std::make_shared<Model>(
                raw.get(), static_cast<uint32_t>(context), threads, std::move(selection));
        raw.release();
        const jlong id = next_id.fetch_add(1);
        if (id <= 0) throw std::runtime_error("native model session IDs exhausted");
        { std::lock_guard<std::mutex> lock(registry_lock); registry.emplace(id, std::move(value)); }
        return id;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_synexia_chromellm_standalone_LlamaCppJniBackend_nOpen(
        JNIEnv* env, jclass, jbyteArray model_path, jint context, jint threads, jint gpu_layers) {
    try {
        return OpenModel(env, model_path, context, threads, gpu_layers);
    } catch (const std::exception& ex) { if (!env->ExceptionCheck()) Fail(env, ex.what()); return 0; }
      catch (...) { if (!env->ExceptionCheck()) Fail(env, "unclassified native model load failure"); return 0; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_synexia_chromellm_standalone_LlamaCppJniBackend_nOpenOnDevice(
        JNIEnv* env, jclass, jbyteArray model_path, jint context, jint threads, jint gpu_layers,
        jbyteArray backend, jbyteArray device) {
    try {
        std::call_once(backend_initialized, [] { llama_backend_init(); });
        auto selection = SelectDevice(Selector(env, backend), Selector(env, device), gpu_layers);
        return OpenModel(env, model_path, context, threads, gpu_layers, std::move(selection));
    } catch (const std::exception& ex) { if (!env->ExceptionCheck()) Fail(env, ex.what()); return 0; }
      catch (...) { if (!env->ExceptionCheck()) Fail(env, "unclassified native device selection failure"); return 0; }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_synexia_chromellm_standalone_LlamaCppJniBackend_nAvailableDevices(JNIEnv* env, jclass) {
    try {
        std::call_once(backend_initialized, [] { llama_backend_init(); });
        std::vector<DeviceSelection> devices;
        const size_t count = DeviceCount();
        devices.reserve(count);
        for (size_t i = 0; i < count; ++i) devices.push_back(DescribeDevice(ggml_backend_dev_get(i)));
        return DeviceFields(env, devices);
    } catch (const std::exception& ex) { if (!env->ExceptionCheck()) Fail(env, ex.what()); return nullptr; }
      catch (...) { if (!env->ExceptionCheck()) Fail(env, "unclassified native device inventory failure"); return nullptr; }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_synexia_chromellm_standalone_LlamaCppJniBackend_nSelectedDevice(JNIEnv* env, jclass, jlong handle) {
    try {
        auto state = Lookup(handle);
        return DeviceFields(env, state->selection ? std::vector<DeviceSelection>{*state->selection}
                                                 : std::vector<DeviceSelection>{});
    } catch (const std::exception& ex) { if (!env->ExceptionCheck()) Fail(env, ex.what()); return nullptr; }
      catch (...) { if (!env->ExceptionCheck()) Fail(env, "unclassified native device observation failure"); return nullptr; }
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

