# ChromeLLM standalone Java 21 inference

The module has no Chrome browser, ChromeML, Node, PowerShell or third-party Java runtime dependency. It uses the JDK modules `java.base` and `java.net.http`. Select the existing local llama.cpp CLI/JNI provider or the hosted Gemini API explicitly. Dawn is an optional native compute dependency for local WebGPU inference.

## Opt-in lazy local model artifacts

A local CLI or JNI backend may now select one **explicitly licensed, SHA-256-pinned HTTPS GGUF**
without bundling or downloading it during Maven/CMake builds. The cache is an existing,
operator-chosen writable directory; no implicit home cache is created. The model is
downloaded at the first inference request and verified before opening llama.cpp.

```sh
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --backend cli --llama-bin /path/to/llama-cli \
  --model-url https://models.example.org/licensed.gguf \
  --model-sha256 YOUR_64_HEX_SHA256 --model-cache-dir /approved/model-cache \
  --prompt-file /path/to/request.txt
```

Provide **all three** of `--model-url`, `--model-sha256`, and `--model-cache-dir`.
They exclude `--model`, `--tier`, and tier-model paths; hosted Gemini rejects them.
Download redirects and provider fallback are forbidden. Cache hits are rehashed;
corrupt entries fail closed instead of being silently overwritten. Native libraries
and executables remain separately installed. See [LAZY_MODEL_CONTRACT.md](LAZY_MODEL_CONTRACT.md)
for byte limits, staging, cache ownership, acquisition timeouts, and the WebMCP/MCP
boundary. This does **not** extract Chrome-managed Gemini Nano weights.



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

The one-shot CLI continues to use an explicitly selected provider.
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
without CPU substitution. With `--backend cli`, `--device` names the llama.cpp
CLI device. With `--backend jni`, `--device` is one half of an exact
`--device-backend`/`--device` pair (see "JNI device inventory and stdio
sessions" below). These flags do not install Dawn, validate a driver, prove actual GPU
offload, or establish numerical parity. The offline tests prove bounded argv,
receipt checks and routing only; real WebGPU inference, driver compatibility,
and performance remain unverified.

## Bounded local requests and cancellation

Local CLI and JNI prompts, including one-shot prompt files, are limited to
16 MiB of strict UTF-8. Malformed UTF-16 Java strings and malformed UTF-8 files
fail instead of being replaced with different characters. The byte budget is
checked before allocating the encoded prompt, and the prompt receipt hashes
those same bytes. This admission limit also applies to direct Java calls;
previously unbounded local prompt files are now rejected above the limit.
Gemini retains its separate 4 MiB serialized request and response limits.

The CLI drains stdout while the child is running and rejects the first byte
beyond 4 MiB. Exactly 4 MiB is accepted; empty or malformed UTF-8 output fails.
Unused stderr diagnostics are discarded by the process redirection, so a verbose
child cannot grow an error file or block on an unread error pipe. The only
per-request scratch file is the prompt. Child stdin is closed because the
prompt file supplies the complete request.

CLI deadline checks cover prompt encoding, file and launch checkpoints, output
collection, decoding and receipt generation. Cancellation terminates the owned
child, closes its streams and removes its prompt. Process termination and a
busy Windows prompt-file handle have separate bounded cleanup waits of up to
five seconds each; cleanup can therefore finish after the inference deadline.
A primary inference error remains primary if cleanup also fails. These
in-process deadlines do not provide operating-system containment of arbitrary
descendant processes.

JNI keeps its existing public synchronized method signatures and all native
entry points. Its deadline starts after the Java method monitor is acquired;
waiting to enter that synchronized method is outside the deadline. The method
body includes encoding, native work, decoding and receipt generation in its
deadline checks, and waiting for a native model lock also checks the remaining
time. Java interruption is preserved and reported as `InterruptedException`.
The native CPU abort callback observes the original caller, including virtual
threads and callbacks on native worker threads. Each native callback thread
uses its own JNI environment. Dawn/GPU cancellation remains cooperative at
supported host checkpoints; this does not forcibly preempt a running GPU kernel.

Provider-specific options must match their backend. CLI rejects `--jni-lib`,
`--context-tokens` and `--threads`; JNI rejects `--llama-bin`. Supported CLI
`--device` and `--gpu-layers` selection remains available. Unsupported options
fail instead of silently appearing to change the selected provider.

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

For prebuilt libraries, CMake first checks whether `libllama` exports the required GGML device APIs itself. Monolithic distributions need no extra libraries. If GGML is split into `ggml` and `ggml-base`, CMake searches beside the selected `LLAMA_LIBRARY` before ordinary library paths. Explicit `GGML_LIBRARY` and `GGML_BASE_LIBRARY` values take precedence. Use headers and libraries from the same compatible build; CMake validates the selected libraries against the device-selection API.

If the split libraries are installed elsewhere, provide their full paths:

```sh
cmake -S standalone/native -B standalone/native/build \
  -DLLAMA_INCLUDE_DIR=/path/to/llama/include \
  -DGGML_INCLUDE_DIR=/path/to/llama/ggml/include \
  -DLLAMA_LIBRARY=/path/to/llama-build/bin/libllama.so \
  -DGGML_LIBRARY=/other/library/directory/libggml.so \
  -DGGML_BASE_LIBRARY=/other/library/directory/libggml-base.so
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
  -DGGML_BACKEND_DL=OFF \
  -DGGML_WEBGPU_DEBUG=OFF \
  -DGGML_WEBGPU_CPU_PROFILE=OFF \
  -DGGML_WEBGPU_GPU_PROFILE=OFF \
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

Alternatively, point `Dawn_DIR` at the directory containing the installed `DawnConfig.cmake`. When building llama.cpp separately for the prebuilt-library path, enable `GGML_WEBGPU=ON` and keep `GGML_BACKEND_DL=OFF`; use the resulting matching llama/GGML libraries above. The pinned [WebGPU build](https://github.com/hsoliwal/synexia-llmcpp/blob/650ac3739d993e1d425f2d5db3c4205b3606cc30/ggml/src/ggml-webgpu/CMakeLists.txt) requires Python 3 to embed WGSL shaders. Keep the three WebGPU debug/profile flags off for binary stdio sessions because those modes can write native diagnostics to stdout. Device enumeration observes registered providers; it does not automatically load backend plugins.

`--compute-backend auto` is the JNI default and preserves the original provider behavior and JNI entry point. `cpu` explicitly selects the CPU provider and requires `--gpu-layers 0`. `webgpu` explicitly selects the WebGPU provider and requires a positive layer count; an absent provider fails instead of silently substituting CPU. These options are valid only with `--backend jni`. The original public Java constructor and JNI `nOpen` entry point remain available.

Selecting a WebGPU device and requesting layers does not measure which model operations execute on the device. Validate actual inference with your chosen GGUF model, Dawn build and driver before making performance or device-execution claims.

## JNI device inventory and stdio sessions

`--list-devices true --jni-lib /absolute/path/libchromellm_llama_jni.so` prints
one line per device registered by the supplied llama.cpp build: backend, device
name, type (`CPU`, `GPU`, `IGPU` or `ACCEL`) and description, tab-separated.
Listing does not require model weights. Control characters in display fields are
escaped; use the exact names reported by the supplied build.

`--backend jni --device-backend <backend> --device <name>` selects exactly one
listed pair. Both options are required together, the match is case-sensitive, a
missing or ambiguous pair fails, a GPU/IGPU device requires a positive
`--gpu-layers` and a CPU device requires `--gpu-layers 0`. The pair cannot be
combined with `--compute-backend`, which selects a whole provider instead.

`--stdio true` keeps the one selected backend and model loaded and serves
sequential requests on stdin/stdout instead of reading `--prompt-file`. Requests
and responses are length-framed binary messages carrying UTF-8 text; frames are
bounded, end of input between frames ends the session, and a partial frame is an
error. A llama.cpp build that prints diagnostics to stdout is incompatible with
this mode.

Stdio clients, including the Synexia ChromeLLM text-model adapter, receive the
`CLM1` magic first. Numeric fields use big-endian encoding:

| Frame | Fields |
| --- | --- |
| Request | 32-bit prompt byte length, 32-bit maximum tokens, 64-bit timeout in milliseconds, UTF-8 prompt bytes |
| Success | 32-bit status `0`, then length-prefixed UTF-8 answer, backend ID and SHA-256 prompt receipt |
| Failure | 32-bit nonzero status, then a length-prefixed UTF-8 error message |

Each length prefix is a 32-bit byte count. Prompts are limited to 16 MiB and
answers to 4 MiB. Failure statuses are `1` for inference/encoding failure, `2`
for timeout, `3` for an invalid request and `4` for interruption. A truncated
frame or partial output write terminates the session; EOF between frames ends
it normally. A client requiring a hard deadline can retire the provider process.

## Connect the existing Synexia Search2API

Search2API owns multi-query retrieval, citations, comparison and visual results.
Its existing ChromeLLM adapter can select this hosted backend while retaining
framed sessions and exact provider/prompt receipts. With the API key already in
the Search2API process environment, configure:

```sh
export SYNEXIA_SEARCH_CHROMELLM_JAR=/absolute/path/to/chromellm-standalone.jar
export SYNEXIA_SEARCH_CHROMELLM_BACKEND=gemini
export SYNEXIA_SEARCH_CHROMELLM_MODEL=gemini-4-hydrogen
export SYNEXIA_SEARCH_CHROMELLM_THINKING_LEVEL=high
export SYNEXIA_SEARCH_CHROMELLM_API_KEY_ENV=GEMINI_API_KEY
```

The key setting is an environment-variable name, never the key value. Hosted
profiles default to 32768 output tokens and can also be configured as a separate
`DEEP_` profile beside a local CLI/JNI model. This sends summarization prompts
and retrieved evidence to the selected Gemini API model; access is separate
from Google's Search AI Mode. See the canonical
[Search2API configuration](https://github.com/hsoliwal/com.synexia/blob/develop/synexia-mllm/nanobrowser-java/docs/SEARCH2API.md)
for profile inheritance and supported options.

## Synthetic JNI test (not a real model run)

```sh
cmake -S standalone/native -B /tmp/chromellm-jni-test -DCHROMELLM_SYNTHETIC_TEST=ON
cmake --build /tmp/chromellm-jni-test
ctest --test-dir /tmp/chromellm-jni-test --output-on-failure
mvn -f standalone/pom.xml test-compile
java -Xcheck:jni -cp standalone/target/classes:standalone/target/test-classes \
 io.synexia.chromellm.standalone.JniSyntheticContractTestMain \
 /tmp/chromellm-jni-test/libchromellm_llama_jni.so
```

The CTest case builds synthetic archives solely for the real CMake compile/link checks. It checks sibling discovery, explicit overrides, ordinary lookup fallback and monolithic-library compatibility, then builds the existing synthetic JNI fixture without external library options. These probe archives contain no model implementation and are never used for inference.

Repeat the same Java command with `CHROMELLM_TEST_NO_WEBGPU=1` in its environment to exercise rejection of an unavailable provider.

The existing CI workflow runs both the Java contracts and synthetic JNI contracts on Linux, Windows and macOS. JNI checks run with `-Xcheck:jni`; a separate 32 MiB heap invocation verifies that oversized prompt encoding fails before allocating an encoded copy. The native fixture links the platform thread library and uses UTF-8 source encoding on MSVC.

These tests check JNI loading, strict Unicode and byte limits, bounded token pieces, prompt receipts, timeout, interruption, native-lock deadlines, independent model close, stale-handle rejection and explicit CPU/WebGPU selection using a **fake test-only llama API**. They do not establish real GGUF inference or GPU offload. `mvn verify` also executes loopback HTTP tests for the Gemini adapter and provider-selection tests, without contacting Google or using credentials. Live API credentials, real models, drivers and device performance require separate runtime verification. Reusable source-change recipes and fixed-point evidence belong to hsoliwal/com.synexia; product code and tests live here.
