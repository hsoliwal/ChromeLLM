# ChromeLLM standalone Java 21 inference

The module has no Chrome browser, ChromeML, Node, PowerShell or third-party Java runtime dependency. It uses the JDK modules `java.base` and `java.net.http`. Select the existing local llama.cpp CLI/JNI provider or the hosted Gemini API explicitly. Dawn is an optional native compute dependency for local WebGPU inference.

## Gemini 4 Hydrogen

Set `GEMINI_API_KEY` in the process environment, then run:

```sh
mvn -f standalone/pom.xml verify
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --backend gemini --model gemini-4-hydrogen --thinking-level high \
  --prompt-file /path/to/request.txt --max-tokens 32768 --timeout-seconds 120
```

The Gemini provider sends the prompt to Google's HTTPS `generateContent` API. It requires no local model file or native library. Credentials are read from `GEMINI_API_KEY`, or from the variable named by `--api-key-env`. API keys and endpoint overrides are not command-line arguments. Transport and API errors omit the key, prompt and server error body.

| Option | Gemini default | Contract |
| --- | --- | --- |
| `--model` | `gemini-4-hydrogen` | Explicit API model identifier; never interpreted as a local path |
| `--thinking-level` | `high` | `low`, `medium`, `high`, `xhigh` (case-insensitive) |
| `--api-key-env` | `GEMINI_API_KEY` | Name of the credential environment variable |
| `--max-tokens` | `32768` | Existing request range 1–32768; includes thinking and visible output |
| `--timeout-seconds` | `120` | Positive whole seconds, at most 3600; covers the full response body |

The provider returns one completed text candidate, with the existing SHA-256 prompt receipt. It excludes thought parts and rejects blocked, truncated, incomplete, non-text, malformed UTF-8 or malformed JSON responses. Both the serialized request and response are bounded to 4 MiB. Redirects, application retries and automatic provider fallback are disabled; closing the backend cancels active calls. A token-budget failure produces an error; select a lower thinking level or a larger budget within the existing request limit.

This is the text inference adapter. It does not implement Google's Search interface, tool execution, multimodal uploads or background long decode. Google documents the supported model and request shape in the [Hydrogen guide](https://aistudio.google.com/docs/whats-new-gemini-4-hydrogen) and [GenerateContent API](https://aistudio.google.com/api-reference/generate-content/generate-content).

## Explicit inference tiers and Dawn/WebGPU CLI selection

The Java 21 `InferenceTier` and `TieredInference` API routes independently
supplied `ModelBackend` instances as `fast`, `reasoning`, or `frontier`.
These are operator-selected *roles*, not Google model identities. Missing
routes, provider failures, and incorrect prompt-hash/backend-ID receipts fail
closed; there is no automatic model fallback or second generation attempt.
Model output remains untrusted candidate text.

The existing one-shot CLI remains unchanged without the following new options.
To select a tier, provide `--tier fast|reasoning|frontier` and the matching
`--fast-model`, `--reasoning-model`, or `--frontier-model` path. Do not mix
`--tier` with `--model`. The selected model is the only model opened.

The existing `hsoliwal/synexia-llmcpp` fork contains GGML's WebGPU backend
that links Dawn. Build and qualify an independently supplied llama.cpp CLI with
`-DGGML_WEBGPU=ON` and a compatible installed Dawn package. First inspect the
runtime's `llama-cli --list-devices` output to learn its *exact* device ID.
Then run (illustrative device name; replace with the discovered value):

```sh
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --backend cli --llama-bin /path/to/llama-cli --tier fast \
  --fast-model /path/to/licensed.gguf --prompt-file /path/to/request.txt \
  --device WebGPU0 --gpu-layers 16 --max-tokens 256
```

The `--device` and `--gpu-layers` options are passed as separate, checked
arguments to the explicitly provided `llama-cli`; unsupported devices fail
without CPU substitution. `--device` is currently **CLI-only**. JNI still
supports its existing `--gpu-layers` hint but does not prove which GPU API
executed. These flags do not install Dawn, validate a driver, prove actual GPU
offload, or establish numerical parity. The offline tests prove bounded argv,
receipt checks and routing only; real WebGPU inference, driver compatibility,
and performance remain unverified.

## Build JNI from an independently licensed llama.cpp

Reference fork: hsoliwal/synexia-llmcpp at commit 650ac3739d993e1d425f2d5db3c4205b3606cc30. The build's library headers and ABI must be compatible; this repository does not vendor llama.cpp or weights.

On Linux:


```sh
mvn -f standalone/pom.xml verify
cmake -S standalone/native -B standalone/native/build \
  -DLLAMA_INCLUDE_DIR=/path/to/llama/include -DGGML_INCLUDE_DIR=/path/to/llama/ggml/include \
  -DLLAMA_LIBRARY=/path/to/libllama.so
cmake --build standalone/native/build --parallel
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --backend jni --jni-lib /absolute/path/libchromellm_llama_jni.so \
  --model /path/to/licensed.gguf --prompt-file /path/to/request.txt \
  --context-tokens 4096 --threads 4 --gpu-layers 0 --max-tokens 256
```

On Windows/macOS, build the platform DLL/dylib and provide correct loader paths for its transitive dependencies. `--backend cli --llama-bin /path/to/llama-cli` remains available. No automatic provider fallback is permitted.

The JNI provider supports decoder-only text models. It uses one model handle per Java backend, a fresh context per bounded call, deterministic greedy decoding, checked UTF-8 and SHA-256 prompt receipts. Per-instance generation and close serialize in Java; native handles are registry-validated. The deadline uses periodic checks and llama.cpp's CPU abort callback; long GPU kernels may not be interruptible immediately. The `--gpu-layers` argument requests offload but cannot prove actual device execution.

## Local Dawn/WebGPU inference

The existing `ggml-webgpu` provider in [synexia-llmcpp](https://github.com/hsoliwal/synexia-llmcpp/tree/650ac3739d993e1d425f2d5db3c4205b3606cc30/ggml/src/ggml-webgpu) consumes the native `dawn::webgpu_dawn` library exported by [Dawn](https://github.com/hsoliwal/dawn). ChromeLLM reuses that provider through its existing JNI bridge. It does not load Chrome-managed DLLs or model assets.

Build and install a compatible Dawn checkout using its [CMake instructions](https://github.com/hsoliwal/dawn/blob/783c427ce925698f4ef9b5699d8c5a17da90486f/docs/quickstart-cmake.md), with `DAWN_ENABLE_INSTALL=ON`. Then supply the installed Dawn package and a local llama.cpp source checkout:

```sh
cmake -S standalone/native -B standalone/native/build-webgpu \
  -DCHROMELLM_LLAMA_SOURCE_DIR=/path/to/synexia-llmcpp \
  -DCHROMELLM_WEBGPU=ON \
  -DCMAKE_PREFIX_PATH=/path/to/dawn/install \
  -DCMAKE_BUILD_TYPE=Release
cmake --build standalone/native/build-webgpu --parallel
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --backend jni --jni-lib /absolute/path/libchromellm_llama_jni.so \
  --model /path/to/licensed.gguf --prompt-file /path/to/request.txt \
  --compute-backend webgpu --gpu-layers 32 --context-tokens 4096 \
  --threads 4 --max-tokens 256
```

The source-build option uses linked GGML providers (`GGML_BACKEND_DL=OFF`) and delegates WebGPU configuration to llama.cpp. ChromeLLM itself does not download source or models. The independently installed Dawn package and its build prerequisites must already be available.

`--compute-backend auto` is the JNI default and preserves the original provider behavior and JNI entry point. `cpu` explicitly selects the CPU provider and requires `--gpu-layers 0`. `webgpu` explicitly selects the WebGPU provider and requires a positive layer count; an absent provider fails instead of silently substituting CPU. These options are valid only with `--backend jni`. The original public Java constructor and JNI `nOpen` entry point remain available.

Selecting a WebGPU device and requesting layers does not measure which model operations execute on the device. Validate actual inference with your chosen GGUF model, Dawn build and driver before making performance or device-execution claims.

## Synthetic JNI test (not a real model run)

```sh
cmake -S standalone/native -B /tmp/chromellm-jni-test -DCHROMELLM_SYNTHETIC_TEST=ON
cmake --build /tmp/chromellm-jni-test
mvn -f standalone/pom.xml test-compile
java -cp standalone/target/classes:standalone/target/test-classes \
 io.synexia.chromellm.standalone.JniSyntheticContractTestMain \
 /tmp/chromellm-jni-test/libchromellm_llama_jni.so
```

Repeat the same Java command with `CHROMELLM_TEST_NO_WEBGPU=1` in its environment to exercise rejection of an unavailable provider.

These tests check JNI loading, UTF-8 input, bounded output, prompt receipt, timeout, close, stale-handle rejection and explicit CPU/WebGPU selection using a **fake test-only llama API**. They do not establish real GGUF inference or GPU offload. `mvn verify` also executes loopback HTTP tests for the Gemini adapter and provider-selection tests, without contacting Google or using credentials. Live API credentials, real models, drivers and device performance require separate runtime verification. Reusable source-change recipes and fixed-point evidence belong to hsoliwal/com.synexia; product code and tests live here.
