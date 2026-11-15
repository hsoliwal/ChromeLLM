# ChromeLLM standalone Java 21 inference

The module has no Chrome browser, ChromeML, Node, PowerShell or third-party Java runtime dependency. Use the browser-free llama.cpp CLI adapter or the in-process llama.cpp JNI adapter with an explicitly supplied, lawfully licensed GGUF model. If you choose a llama.cpp build with WebGPU, it uses your existing, independently built Dawn native library. The JNI code never downloads models, discovers browser assets or switches providers after a failure.

## Build JNI against your llama.cpp installation

Reference fork: [hsoliwal/synexia-llmcpp at 650ac3739d993e1d425f2d5db3c4205b3606cc30](https://github.com/hsoliwal/synexia-llmcpp/tree/650ac3739d993e1d425f2d5db3c4205b3606cc30). Use headers and libraries from the same compatible build. This repository does not vendor llama.cpp, Dawn or weights.

On Linux, with a JDK 21 development installation and a compatible llama.cpp build:

```sh
mvn -f standalone/pom.xml verify
cmake -S standalone/native -B standalone/native/build \
  -DLLAMA_INCLUDE_DIR=/path/to/llama/include \
  -DGGML_INCLUDE_DIR=/path/to/llama/ggml/include \
  -DLLAMA_LIBRARY=/path/to/llama-build/bin/libllama.so
cmake --build standalone/native/build --parallel
```

The bridge links `llama`, `ggml` and `ggml-base`. When you supply `LLAMA_LIBRARY`, CMake first looks beside that library for `ggml` and `ggml-base`, then uses ordinary library discovery if either is absent. Explicit `GGML_LIBRARY` and `GGML_BASE_LIBRARY` values take precedence. If your installation puts them elsewhere, add their full paths:

```sh
cmake -S standalone/native -B standalone/native/build \
  -DLLAMA_INCLUDE_DIR=/path/to/llama/include \
  -DGGML_INCLUDE_DIR=/path/to/llama/ggml/include \
  -DLLAMA_LIBRARY=/path/to/llama-build/bin/libllama.so \
  -DGGML_LIBRARY=/other/library/directory/libggml.so \
  -DGGML_BASE_LIBRARY=/other/library/directory/libggml-base.so
```

On Windows, supply the corresponding import-library paths and load the JNI DLL. On macOS, supply the dylib paths. Keep the chosen native engine and its transitive libraries available through the platform loader's search path. `--backend cli --llama-bin /path/to/llama-cli` also remains available.

## Use the existing Dawn WebGPU backend

The pinned llama.cpp fork already implements WebGPU through Dawn. Its [native WebGPU build](https://github.com/hsoliwal/synexia-llmcpp/blob/650ac3739d993e1d425f2d5db3c4205b3606cc30/ggml/src/ggml-webgpu/CMakeLists.txt) uses `find_package(Dawn REQUIRED)` and links `dawn::webgpu_dawn`. Point that build at the directory containing your installed `DawnConfig.cmake`. ChromeLLM selects the resulting registered backend through its existing JNI loader.

For an existing checkout of the pinned llama.cpp source:

```sh
cmake -S /path/to/llama -B /path/to/llama-build \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DGGML_WEBGPU=ON -DGGML_BACKEND_DL=OFF \
  -DDawn_DIR=/path/to/dawn/lib/cmake/Dawn \
  -DGGML_WEBGPU_DEBUG=OFF \
  -DGGML_WEBGPU_CPU_PROFILE=OFF \
  -DGGML_WEBGPU_GPU_PROFILE=OFF
cmake --build /path/to/llama-build --target llama --config Release --parallel
```

`Dawn_DIR` is a llama.cpp build input. The source build also requires Python 3 to embed its WGSL shaders. Use the resulting llama/GGML libraries in the JNI build above. Shared-library locations can include a configuration directory on generators such as Visual Studio.

Keep `GGML_BACKEND_DL=OFF` for this integration: ChromeLLM enumerates already registered backends and does not automatically load backend plugins. The three WebGPU debug/profile flags must remain off when using binary stdio mode because those modes can print native diagnostics to stdout.

### Inspect and select an observed device

Listing devices does not require model weights:

```sh
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --backend jni --jni-lib /absolute/path/libchromellm_llama_jni.so \
  --list-devices true
```

Each tab-separated row contains the registered backend, exact device name, device type and description. Control characters in display fields are escaped. Use the names actually returned by your build; an installed Dawn package alone does not establish that a usable WebGPU device is registered.

For a listed `WebGPU` backend, replace the device placeholder below with its exact, case-sensitive name:

```sh
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --backend jni --jni-lib /absolute/path/libchromellm_llama_jni.so \
  --model /path/to/licensed.gguf --prompt-file /path/to/request.txt \
  --device-backend WebGPU --device "<exact device name from the listing>" \
  --context-tokens 4096 --threads 4 --gpu-layers 1 --max-tokens 256
```

Explicit GPU or integrated-GPU selection requires a positive `--gpu-layers` value. Explicit CPU selection requires zero. Unknown, ambiguous or incorrectly cased selections fail. Omitting both device options preserves automatic selection and the existing default of zero GPU layers.

A selected GPU is a configured offload target. llama.cpp can still execute unsupported operations on CPU, so device selection is not a measurement of GPU execution or speed.

## Keep the model loaded across requests

Use `--stdio true` for an existing local client that implements the binary protocol, including the Synexia ChromeLLM text-model adapter. Supply the same model, JNI library and device options as above, and omit `--prompt-file`:

```sh
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --backend jni --jni-lib /absolute/path/libchromellm_llama_jni.so \
  --model /path/to/licensed.gguf --stdio true \
  --device-backend WebGPU --device "<exact device name from the listing>" \
  --context-tokens 8192 --threads 4 --gpu-layers 1
```

Stdio mode is a binary service, not an interactive text prompt. One backend/model remains loaded for sequential requests; each inference call creates a fresh context. The service sends the `CLM1` magic first. All numeric fields use big-endian encoding.

| Frame | Fields |
| --- | --- |
| Request | 32-bit prompt byte length, 32-bit maximum tokens, 64-bit timeout in milliseconds, UTF-8 prompt bytes |
| Success | 32-bit status `0`, then length-prefixed UTF-8 answer, backend ID and SHA-256 prompt receipt |
| Failure | 32-bit nonzero status, then a length-prefixed UTF-8 error message |

Each length prefix is a 32-bit byte count. Prompts are limited to 16 MiB and answers to 4 MiB. Failure statuses are `1` for inference/encoding failure, `2` for timeout, `3` for an invalid request and `4` for interruption. A truncated frame or partial output write terminates the session. EOF between frames closes it normally.

The JNI provider supports decoder-only text models, deterministic greedy decoding and checked UTF-8 responses. Per-instance generation and close serialize in Java, and native handles are registry-validated. Its deadline uses periodic checks and llama.cpp's CPU abort callback; a long GPU operation may not be immediately interruptible. Clients that require a hard request boundary can retire the provider process, as the Synexia adapter does.

## Synthetic tests

These tests use a fake, test-only llama API and do not perform trained-model inference or GPU execution:

```sh
cmake -S standalone/native -B /tmp/chromellm-jni-test \
  -DCHROMELLM_SYNTHETIC_TEST=ON
cmake --build /tmp/chromellm-jni-test --parallel
ctest --test-dir /tmp/chromellm-jni-test --output-on-failure
mvn -f standalone/pom.xml test-compile
java -cp standalone/target/classes:standalone/target/test-classes \
  io.synexia.chromellm.standalone.JniSyntheticContractTestMain \
  /tmp/chromellm-jni-test/libchromellm_llama_jni.so
```

The CTest case configures the real build against isolated, configure-only library placeholders. It checks sibling discovery, explicit overrides and ordinary lookup fallback, then builds the existing synthetic JNI fixture without external library options. The JNI suite checks device-selection parameters, UTF-8 input/output, deadlines and model-handle lifecycle. `mvn verify` also runs the standalone and framed-transport contracts.

Never distribute the synthetic fixture as an inference engine. These tests do not establish real GGUF inference, Dawn/WebGPU execution, model quality or a performance improvement. Reusable recipes and qualification evidence belong to `hsoliwal/com.synexia`; product code, usage documentation and normal tests live here.
