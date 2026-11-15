// SPDX-License-Identifier: MIT
// Independent llama.cpp JNI provider. Uses only backends registered by the supplied llama.cpp
// build; optional native Dawn through GGML WebGPU. No browser assets.
#include <jni.h>
#include <llama.h>
#include <ggml-backend.h>

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
#include <utility>
#include <vector>

namespace {
constexpr size_t kMaxOutput = 4u * 1024u * 1024u;
using Clock = std::chrono::steady_clock;

struct DeadlineExceeded : std::runtime_error {
    DeadlineExceeded() : std::runtime_error("model inference exceeded configured deadline") {}
};
struct InferenceInterrupted : std::runtime_error {
    InferenceInterrupted() : std::runtime_error("native inference interrupted") {}
};

// The callback can run on a GGML worker. Only the JavaVM and global references cross
// threads; each callback obtains its own JNIEnv and detaches only threads it attached.
class AbortState {
    JNIEnv* owner_env_;
    JavaVM* vm_ = nullptr;
    jclass thread_class_ = nullptr;
    jobject caller_ = nullptr;
    jmethodID interrupted_method_ = nullptr;
    Clock::time_point deadline_;
    std::atomic<bool> interrupted_{false};
    std::atomic<bool> observation_failed_{false};

    void release() noexcept {
        if (caller_ != nullptr) owner_env_->DeleteGlobalRef(caller_);
        if (thread_class_ != nullptr) owner_env_->DeleteGlobalRef(thread_class_);
    }
public:
    AbortState(JNIEnv* env, Clock::time_point deadline) : owner_env_(env), deadline_(deadline) {
        try {
            if (env->GetJavaVM(&vm_) != JNI_OK) throw std::runtime_error("unable to observe JVM interruption");
            jclass local_class = env->FindClass("java/lang/Thread");
            if (local_class == nullptr) throw std::runtime_error("unable to find Java thread class");
            thread_class_ = static_cast<jclass>(env->NewGlobalRef(local_class));
            env->DeleteLocalRef(local_class);
            if (thread_class_ == nullptr) throw std::runtime_error("unable to retain Java thread class");
            jmethodID current = env->GetStaticMethodID(thread_class_, "currentThread", "()Ljava/lang/Thread;");
            if (current == nullptr) throw std::runtime_error("unable to observe current Java thread");
            interrupted_method_ = env->GetMethodID(thread_class_, "isInterrupted", "()Z");
            if (interrupted_method_ == nullptr) throw std::runtime_error("unable to observe interrupt status");
            jobject local_thread = env->CallStaticObjectMethod(thread_class_, current);
            if (env->ExceptionCheck() || local_thread == nullptr)
                throw std::runtime_error("unable to obtain current Java thread");
            caller_ = env->NewGlobalRef(local_thread);
            env->DeleteLocalRef(local_thread);
            if (caller_ == nullptr) throw std::runtime_error("unable to retain current Java thread");
        } catch (...) {
            release();
            throw;
        }
    }
    AbortState(const AbortState&) = delete;
    AbortState& operator=(const AbortState&) = delete;
    ~AbortState() { release(); } // The context and all callbacks have retired before this owner.

    bool stop() noexcept {
        if (interrupted_.load(std::memory_order_relaxed)
                || observation_failed_.load(std::memory_order_relaxed) || Clock::now() >= deadline_) return true;
        JNIEnv* env = nullptr;
        const jint state = vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        bool attached = false;
        if (state == JNI_EDETACHED) {
            if (vm_->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
                observation_failed_.store(true, std::memory_order_relaxed);
                return true;
            }
            attached = true;
        } else if (state != JNI_OK) {
            observation_failed_.store(true, std::memory_order_relaxed);
            return true;
        }
        // A previously attached callback thread may already have an exception. Preserve it
        // and fail observation without invoking another Java method with it pending.
        jboolean interrupted = JNI_FALSE;
        if (!env->ExceptionCheck()) {
            // Use Thread's status query, not a user Thread subclass override. It does not clear
            // the flag. The class and method ID are resolved together from java.lang.Thread.
            interrupted = env->CallNonvirtualBooleanMethod(caller_, thread_class_, interrupted_method_);
        }
        if (env->ExceptionCheck()) {
            observation_failed_.store(true, std::memory_order_relaxed);
            // A foreign callback cannot propagate its pending exception through the caller's
            // JNIEnv. Fail the inference and leave no exception pending when detaching it.
            if (attached) env->ExceptionClear();
        } else if (interrupted == JNI_TRUE) {
            interrupted_.store(true, std::memory_order_relaxed);
        }
        if (attached && vm_->DetachCurrentThread() != JNI_OK)
            observation_failed_.store(true, std::memory_order_relaxed);
        return interrupted_.load(std::memory_order_relaxed)
                || observation_failed_.load(std::memory_order_relaxed) || Clock::now() >= deadline_;
    }
    void check() {
        if (!stop()) return;
        if (interrupted_.load(std::memory_order_relaxed)) throw InferenceInterrupted();
        if (observation_failed_.load(std::memory_order_relaxed))
            throw std::runtime_error("unable to observe Java interrupt status");
        throw DeadlineExceeded();
    }
    Clock::time_point next_poll() const {
        return std::min(deadline_, Clock::now() + std::chrono::milliseconds(10));
    }
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
    // Offload list of a compute-backend policy; llama_model keeps a pointer into it.
    std::vector<ggml_backend_dev_t> devices;
    // One explicitly selected backend/device pair, or null for automatic and policy selection.
    std::shared_ptr<DeviceSelection> selection;
    std::timed_mutex inference_mutex;
    Model(llama_model* m, uint32_t c, int t, std::vector<ggml_backend_dev_t> policy_devices,
          std::shared_ptr<DeviceSelection> selected)
        : model(m), vocab(llama_model_get_vocab(m)), context_tokens(c), threads(t),
          devices(std::move(policy_devices)), selection(std::move(selected)) {
        if (vocab == nullptr) throw std::runtime_error("model contains no vocabulary");
    }
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
bool AbortCallback(void* user) {
    return static_cast<AbortState*>(user)->stop();
}
std::string Generate(Model& state, const std::vector<uint8_t>& input, int max_tokens,
                     AbortState& abort) {
    if (input.empty() || input.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max()))
        throw std::invalid_argument("prompt is empty or too long");
    abort.check();
    std::unique_lock<std::timed_mutex> guard(state.inference_mutex, std::defer_lock);
    while (!guard.try_lock_until(abort.next_poll())) abort.check();
    abort.check();
    const std::string prompt(input.begin(), input.end());
    if (prompt.find('\0') != std::string::npos) throw std::invalid_argument("prompt has NUL byte");
    const int32_t required = llama_tokenize(state.vocab, prompt.data(),
            static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    abort.check();
    if (required >= 0 || required == std::numeric_limits<int32_t>::min())
        throw std::runtime_error("unable to size prompt tokens");
    const int32_t n_prompt = -required;
    if (n_prompt < 1 || static_cast<uint64_t>(n_prompt) + max_tokens > state.context_tokens)
        throw std::invalid_argument("prompt and requested output exceed configured context");
    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(state.vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                       tokens.data(), n_prompt, true, true) != n_prompt)
        throw std::runtime_error("prompt tokenization failed");
    abort.check();

    llama_context_params p = llama_context_default_params();
    p.n_ctx = state.context_tokens;
    p.n_batch = std::min<uint32_t>(state.context_tokens, 512);
    p.n_threads = state.threads;
    p.n_threads_batch = state.threads;
    // The pinned llama.cpp abort callback cooperates with CPU execution. GPU work may
    // finish its current operation before the host-side checks below can observe abort.
    p.abort_callback = AbortCallback;
    p.abort_callback_data = &abort;
    std::unique_ptr<llama_context, decltype(&llama_free)> ctx(
            llama_init_from_model(state.model, p), &llama_free);
    if (!ctx) throw std::runtime_error("unable to allocate llama.cpp context");
    abort.check();
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), &llama_sampler_free);
    if (!sampler) throw std::runtime_error("unable to create token sampler");
    llama_sampler* greedy = llama_sampler_init_greedy();
    if (!greedy) throw std::runtime_error("unable to allocate greedy sampler");
    llama_sampler_chain_add(sampler.get(), greedy);
    for (int32_t start = 0; start < n_prompt; ) {
        abort.check();
        const int32_t count = std::min<int32_t>(n_prompt - start, static_cast<int32_t>(p.n_batch));
        if (llama_decode(ctx.get(), llama_batch_get_one(tokens.data() + start, count)) != 0) {
            abort.check();
            throw std::runtime_error("prompt decode failed");
        }
        start += count;
    }
    std::string output;
    for (int i = 0; i < max_tokens; ++i) {
        abort.check();
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
            if (n < 0 || static_cast<size_t>(n) > larger.size()
                    || output.size() + static_cast<size_t>(n) > kMaxOutput)
                throw std::runtime_error("token piece exceeds output limit");
            output.append(larger.data(), static_cast<size_t>(n));
        } else {
            if (static_cast<size_t>(n) > sizeof(piece))
                throw std::runtime_error("token piece exceeds supplied buffer");
            if (output.size() + static_cast<size_t>(n) > kMaxOutput)
                throw std::runtime_error("native inference exceeds output limit");
            output.append(piece, static_cast<size_t>(n));
        }
        if (i + 1 < max_tokens) {
            abort.check();
            if (llama_decode(ctx.get(), llama_batch_get_one(&token, 1)) != 0) {
                abort.check();
                throw std::runtime_error("generation decode failed");
            }
        }
    }
    abort.check();
    return output;
}
// compute_backend: 0 automatic, 1 CPU only, 2 every registered WebGPU device. An explicit
// backend/device selection is a separate request and is only combined with compute_backend 0.
jlong OpenModel(JNIEnv* env, jbyteArray model_path, jint context, jint threads, jint gpu_layers,
                jint compute_backend, std::shared_ptr<DeviceSelection> selection = nullptr) {
        if (context < 32 || context > 1'048'576 || threads < 1 || threads > 1024
                || gpu_layers < 0 || gpu_layers > 1024 || compute_backend < 0 || compute_backend > 2)
            throw std::invalid_argument("invalid native model settings");
        if (compute_backend != 0 && selection)
            throw std::invalid_argument("a compute backend policy and an explicit device are exclusive");
        if (compute_backend == 1 && gpu_layers != 0)
            throw std::invalid_argument("cpu requires zero GPU layers");
        if (compute_backend == 2 && gpu_layers == 0)
            throw std::invalid_argument("webgpu requires a positive GPU layer count");
        const auto bytes = Bytes(env, model_path);
        const std::string path(bytes.begin(), bytes.end());
        if (path.empty() || path.find('\0') != std::string::npos)
            throw std::invalid_argument("invalid model path");
        std::call_once(backend_initialized, [] { llama_backend_init(); });
        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = gpu_layers;
        std::vector<ggml_backend_dev_t> devices;
        if (compute_backend == 2) {
            // Use the devices already registered by GGML. Calling the provider's device factory
            // again can reinitialize native GPU state. No plugin-directory search or download.
            ggml_backend_reg_t webgpu = ggml_backend_reg_by_name("WebGPU");
            if (webgpu == nullptr)
                throw std::runtime_error("GGML WebGPU provider is unavailable; build llama.cpp with GGML_WEBGPU=ON and native Dawn");
            const size_t count = DeviceCount();
            for (size_t i = 0; i < count; ++i) {
                ggml_backend_dev_t device = ggml_backend_dev_get(i);
                if (device != nullptr && ggml_backend_dev_backend_reg(device) == webgpu)
                    devices.push_back(device);
            }
            if (devices.empty())
                throw std::runtime_error("GGML WebGPU provider has no registered device");
        }
        if (compute_backend != 0) {
            // An empty, terminated list explicitly disables GPU selection for CPU policy.
            devices.push_back(nullptr);
            params.devices = devices.data();
            params.split_mode = LLAMA_SPLIT_MODE_NONE;
            // llama.cpp validates main_gpu even with zero offload layers. Its negative
            // sentinel selects CPU when the explicitly supplied device list is empty.
            params.main_gpu = compute_backend == 1 ? -1 : 0;
        } else if (selection) {
            params.devices = selection->offload_devices.data();
            params.split_mode = LLAMA_SPLIT_MODE_NONE;
            params.main_gpu = selection->type == "CPU" ? -1 : 0;
        }
        // Own the model even when validation or shared_ptr allocation throws.
        std::unique_ptr<llama_model, decltype(&llama_model_free)> raw(
                llama_model_load_from_file(path.c_str(), params), &llama_model_free);
        if (!raw) throw std::runtime_error("llama.cpp could not load the supplied model");
        if (llama_model_has_encoder(raw.get()) || !llama_model_has_decoder(raw.get()))
            throw std::invalid_argument("this JNI provider currently supports decoder-only models");
        // Moving the vector keeps its heap buffer, so params.devices stays valid inside the model.
        std::shared_ptr<Model> value = std::make_shared<Model>(
                raw.get(), static_cast<uint32_t>(context), threads, std::move(devices), std::move(selection));
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
        return OpenModel(env, model_path, context, threads, gpu_layers, 0);
    } catch (const std::exception& ex) { if (!env->ExceptionCheck()) Fail(env, ex.what()); return 0; }
      catch (...) { if (!env->ExceptionCheck()) Fail(env, "unclassified native model load failure"); return 0; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_synexia_chromellm_standalone_LlamaCppJniBackend_nOpenWithBackend(
        JNIEnv* env, jclass, jbyteArray model_path, jint context, jint threads, jint gpu_layers,
        jint compute_backend) {
    try {
        return OpenModel(env, model_path, context, threads, gpu_layers, compute_backend);
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
        return OpenModel(env, model_path, context, threads, gpu_layers, 0, std::move(selection));
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
        const Clock::time_point deadline = Clock::now() + std::chrono::milliseconds(timeout_ms);
        AbortState abort(env, deadline);
        abort.check();
        const auto bytes = Bytes(env, prompt);
        abort.check();
        auto state = Lookup(handle);
        const std::string text = Generate(*state, bytes, tokens, abort);
        jbyteArray result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result != nullptr && !text.empty()) env->SetByteArrayRegion(result, 0,
            static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        if (!env->ExceptionCheck()) abort.check();
        return result;
    } catch (const InferenceInterrupted& ex) {
        if (!env->ExceptionCheck()) Throw(env, "java/lang/InterruptedException", ex.what());
        return nullptr;
    } catch (const DeadlineExceeded& ex) {
        if (!env->ExceptionCheck()) Throw(env, "java/util/concurrent/TimeoutException", ex.what());
        return nullptr;
    } catch (const std::exception& ex) { if (!env->ExceptionCheck()) Fail(env, ex.what()); return nullptr; }
      catch (...) { if (!env->ExceptionCheck()) Fail(env, "unclassified native generation failure"); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_synexia_chromellm_standalone_LlamaCppJniBackend_nClose(JNIEnv* env, jclass, jlong handle) {
    try {
        std::shared_ptr<Model> closing;
        {
            std::lock_guard<std::mutex> lock(registry_lock);
            auto found = registry.find(handle);
            if (found == registry.end()) return;
            closing = std::move(found->second);
            registry.erase(found);
        }
        // Device teardown can be slow. It must not hold the global handle registry lock;
        // shared ownership still keeps an independently admitted inference alive.
    } catch (const std::exception& ex) {
        if (!env->ExceptionCheck()) Throw(env, "java/lang/IllegalStateException", ex.what());
    } catch (...) {
        if (!env->ExceptionCheck()) Throw(env, "java/lang/IllegalStateException", "native model close failed");
    }
}
