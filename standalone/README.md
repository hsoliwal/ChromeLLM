# ChromeLLM standalone Java 21 inference

The module has no third-party Java runtime dependency. It uses the JDK modules `java.base`, `java.net.http` and `jdk.httpserver`. Select the local llama.cpp CLI/JNI or hosted Gemini API provider explicitly. The module has no browser code; Dawn is an optional native compute dependency for local WebGPU inference.

For optional analysis around a project build, use the reusable
[pre-build and post-build GitHub Action](BUILD_AI_ACTION.md). It invokes this
same Java CLI, records advice separately, and preserves the actual build result.

## Opt-in lazy local model artifacts

A local CLI or JNI backend may now select one **explicitly licensed, SHA-256-pinned HTTPS GGUF**
without bundling it in the Java artifact. Ordinary Maven/CMake builds do not fetch weights;
the explicit `real-model-test` Maven profile below downloads and verifies its pinned test model.
The CLI cache is an existing,
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
Model downloads follow at most five HTTPS redirects, with no credentials or fragments.
Provider selection remains explicit. Cache hits are rehashed;
corrupt entries fail closed instead of being silently overwritten. Native libraries
and executables remain separately installed. See [LAZY_MODEL_CONTRACT.md](LAZY_MODEL_CONTRACT.md)
for byte limits, staging, cache ownership, acquisition timeouts, and the WebMCP/MCP
boundary. This does **not** extract Chrome-managed Gemini Nano weights.



## Maven downloads and tests a real model

The explicit `real-model-test` profile resolves ChromeLLM's published `weights.bin`,
checks its full SHA-256 and exact byte length, and runs real CPU inference under
`-Xcheck:jni`. It shares the runtime's verified model cache. A second Maven invocation
with `-o` reuses the verified bytes and fails on a missing or corrupt cache entry.

```sh
mvn -B -ntp -f standalone/pom.xml -Pfork-native,real-model-test \
  -Dchromellm.fork.source=/absolute/path/to/your/llama.cpp-checkout \
  -Dchromellm.fork.commit=YOUR_CHECKOUT_FULL_40_HEX_COMMIT verify
```

Maven drives native compilation, model acquisition and inference verification in that
command. The model defaults to SmolLM2-135M-Instruct Q4_K_M: 105,454,144 bytes with
SHA-256 `8030f04528538d47bda434f6f0bdf3952c40a58123e4d5e755332f23731a8684`.
The native checkout is an explicitly supplied source input. Public GitHub CI checks out
its pinned public donor automatically, then runs this command and an offline smart-build
recipe using the same downloaded model. See [MAVEN_MODEL_TEST.md](MAVEN_MODEL_TEST.md)
for cache, platform, model and recipe settings.

## MCP JSON-RPC stdio (opt-in)

Standalone mode now exposes an MCP-compatible stdio server separately from the legacy
`--stdio true` CLM1 binary transport. The MCP server supports an explicit
`2025-06-18` initialization handshake, `tools/list`, `tools/call`,
`ping`, and three bounded tools: `backend_status`, `infer_text`, and `plan_steps`.
The planning tool returns candidate steps without executing them; its admission
rules are documented in [AGENT_PLANNING_CONTRACT.md](AGENT_PLANNING_CONTRACT.md).
The stdio transport does not require Node or a browser.

```sh
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --mcp-stdio true --backend cli --llama-bin /path/to/llama-cli \
  --model /path/to/licensed.gguf
```

Alternatively, replace the local `--model` argument with the three explicit
lazy-acquisition flags `--model-url`, `--model-sha256`, `--model-cache-dir`.
MCP initialization and listing tools do not download or initialize a lazy model;
`infer_text` initiates acquisition at first use.

This endpoint is an MCP **server**; ChromeLLM has no browser-page (WebMCP)
integration. The existing CLM1 framing remains available,
unmodified. The protocol restrictions and error semantics are specified in
[MCP_STDIO_CONTRACT.md](MCP_STDIO_CONTRACT.md).

## Build and consume native Maven artifacts

The `native` Maven profile builds the existing source forks, installs the JNI
runtime and its native dependencies into a resource staging directory, and
attaches a platform/backend JAR to the normal Java artifact. It uses the existing
llama.cpp provider and its Dawn/WebGPU integration. Model files are separate.

Use a full JDK 21, Node.js 22 for the fixed script contract tests, CMake 3.22 or newer, and a compatible C/C++ toolchain. A Dawn
source build also needs Python 3, a C++20 compiler, and the platform prerequisites
documented by the pinned Dawn checkout. The ordinary command remains:

```sh
mvn -f standalone/pom.xml verify
```

It builds Java and runs offline contract tests without fetching or compiling a
native engine. To build the CPU native artifact as well:

```sh
mvn -f standalone/pom.xml -Pnative verify
```

The first native build fetches `hsoliwal/synexia-llmcpp` at the complete commit
declared in the POM. Git must already have access to that fork; credentials are
handled by Git's normal credential configuration. There is no anonymous upstream
fallback. Source downloads occur only in the explicitly selected native build,
under `target/native-build/sources`, and later builds reuse that checkout. Maven
`clean` removes that build cache. An existing checkout can be used without Git
downloads:

```sh
mvn -f standalone/pom.xml -Pnative \
  -Dchromellm.llama.source=/absolute/path/to/synexia-llmcpp \
  -Dchromellm.native.fetch=false verify
```

Supplied checkouts remain caller-owned and are never reset or rewritten. They
must provide compatible headers and source; the fork's CMake build and JNI
compiler validate that contract. Downloaded checkouts are pinned to complete
40-character Git commits. Custom revision properties also require complete
commit IDs.

### Dawn/WebGPU build

```sh
mvn -f standalone/pom.xml -Pnative \
  -Dchromellm.native.backend=webgpu verify
```

This builds and installs the pinned Dawn source first, then builds the existing
GGML WebGPU provider and JNI bridge against it. Dawn's own dependency fetcher
uses the selected checkout's dependency definitions. Optional browser windowing,
samples, tests, Node bindings and command-line shader tools are excluded from
this runtime build.

Use `-Dchromellm.dawn.source=/path/to/dawn` for an existing source tree, or
`-Dchromellm.dawn.prefix=/path/to/dawn/install` for an installed CMake package.
Source and prefix are mutually exclusive. An installed package must be accompanied
by its complete dependency license/notice bundle, supplied with
`-Dchromellm.dawn.licenses=/path/to/notices` or under `<prefix>/share/licenses`.
Source builds collect the available source/dependency license texts. Native JARs
carry these notices alongside the project's notices, without source trees or
model weights.

The existing `native-dawn` profile is also preserved: it downloads its separately
pinned Dawn release binary on its supported hosts. Combining `-Pnative,native-dawn`
selects WebGPU and feeds that verified prefix into the artifact build instead of
building Dawn from source. Supply the complete dependency notices as above;
the archive installer verifies binary/cache integrity but does not establish
notice completeness. See [the pinned Dawn downloader](NATIVE_MAVEN_BUILD.md#lazy-verified-dawn-artifacts)
for release digests and platform limits.

### Artifact selection and Java use

The Java coordinates remain
`io.synexia.chromellm:chromellm-standalone:0.1.0-SNAPSHOT`. Native artifacts use
those same coordinates with a classifier combining the platform and backend:

| Platform | CPU classifier | WebGPU classifier |
| --- | --- | --- |
| Linux x86-64 | `linux-x86_64-cpu` | `linux-x86_64-webgpu` |
| Linux ARM64 | `linux-aarch64-cpu` | `linux-aarch64-webgpu` |
| Windows x86-64 | `windows-x86_64-cpu` | `windows-x86_64-webgpu` |
| macOS x86-64 | `macos-x86_64-cpu` | `macos-x86_64-webgpu` |
| macOS ARM64 | `macos-aarch64-cpu` | `macos-aarch64-webgpu` |

Classifiers describe the build target; they do not certify every operating-system
version, driver or model. CPU and WebGPU packages have distinct identities, so
building one cannot overwrite the other in a Maven repository. Select exactly
one native artifact for a process. Its resources are validated against the
running operating system and architecture.

`mvn ... -Pnative install` installs both artifacts into the selected local Maven
repository. Once installed or published to your configured Maven repository,
consume the Java artifact and the chosen native runtime, for example on Linux:

```xml
<dependency>
  <groupId>io.synexia.chromellm</groupId>
  <artifactId>chromellm-standalone</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>io.synexia.chromellm</groupId>
  <artifactId>chromellm-standalone</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <classifier>linux-x86_64-cpu</classifier>
  <scope>runtime</scope>
</dependency>
```

Java callers can enumerate the installed runtime with
`LlamaCppJniBackend.availableDevices()` or create the existing backend using
`LlamaCppJniBackend.fromPackagedRuntime(modelPath, contextTokens, threads, gpuLayers)`.
The existing constructors accepting an explicit JNI library path remain available.

The CLI also resolves the installed native artifact when `--jni-lib` is omitted:

```sh
java --module-path "standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar:standalone/target/chromellm-standalone-0.1.0-SNAPSHOT-linux-x86_64-cpu.jar" \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --list-devices true
```

Use the matching classifier and `;` as the module-path separator on Windows.
Ordinary classpath execution is also supported. No `--add-modules` flag is
needed for the native resource module. Inference still needs a compatible local
model; WebGPU execution still requires explicit provider selection and a working
driver.

Native extraction occurs on first JNI use. The loader checks the package
manifest, streams each library into a private digest-addressed cache, verifies
SHA-256 on extraction and reuse, and coordinates concurrent JVMs with a file
lock. Override the default `~/.cache/chromellm/native` location with the JVM
property `-Dchromellm.native.cache=/path/to/cache`. Ambiguous artifacts, incorrect
classifiers, changed files, unsafe paths and incomplete native dependency
closures fail explicitly. This path never downloads executable code or changes
`java.library.path`.

### Maven downloads a published native binary

When the matching native artifact is already installed or published in a Maven
repository configured in your settings, let Maven resolve it directly:

```sh
mvn -f standalone/pom.xml -Psmart-native-download,smart-build \
  -Dchromellm.smart.recipe=examples/smart-build-packaged-smoke.properties verify
```

`smart-native-download` resolves the selected platform/backend classifier through
Maven's dependency resolver and copies it to `target/smart-runtime/chromellm-native.jar`.
It does not run a C/C++ build. The default version is the current project version;
set `-Dchromellm.smart.nativeVersion=VERSION` to select a published version. The
artifact must exist in the configured repository; this project does not assume
that a native package has been released to Maven Central.

For a native JAR downloaded from GitHub Actions, use its verified local path:

```sh
mvn -f standalone/pom.xml -Psmart-build \
  -Dchromellm.smart.nativeArtifact=/absolute/path/to/chromellm-native.jar \
  -Dchromellm.smart.recipe=examples/smart-build-packaged-smoke.properties verify
```

The packaged loader verifies native resources and selects the running platform.
The JAR and its Maven repository or GitHub artifact source must be trusted, since
this is executable native code.

### Download a model and run inference during the build

The included `examples/smart-build-packaged-smoke.properties` selects the small GGML
`stories260K.gguf` test fixture at an immutable repository revision and SHA-256.
It enables bounded HTTPS redirects, limits the artifact to 2 MiB and the complete
download to 120 seconds, and runs a short CPU prompt. It is a runtime smoke test,
not a quality benchmark. Model bytes stay in `target/model-cache` and are never
added to the Java/native JARs or source repository.

Build the runtime from source and run that same recipe in one Maven invocation:

```sh
mvn -f standalone/pom.xml -Pnative,smart-build \
  -Dchromellm.llama.source=/absolute/path/to/synexia-llmcpp \
  -Dchromellm.native.fetch=false \
  -Dchromellm.smart.recipe=examples/smart-build-packaged-smoke.properties verify
```

Smart-build resolves relative prompt/model/cache paths against the standalone
project. It creates a missing cache only under Maven's `target` directory;
explicit external cache paths retain the validated online preparation behavior;
offline cache paths must already exist. Inference writes a candidate and a
receipt under `target/smart-build`, recording recipe, prompt, and output hashes.
Repeating a recipe checks those identities and refuses a conflicting output.
A warm model cache is rehashed and can be reused without contacting the model
host. Generated text is never applied to project source automatically.

The native fork's existing Java runtime workflow builds and uploads the Java and
CPU native JARs. A fresh consumer job downloads both artifacts, checks their
SHA-256 sums, invokes the Maven recipe twice, and independently executes the
downloaded JARs. Its integration test also exercises a cold HTTPS model download
followed by cache reuse with an unreachable model URL. The ordinary ChromeLLM
workflow keeps Java and synthetic packaging checks across Linux, Windows and
macOS; those checks do not claim real GPU inference.

### Build controls and verification

| Property | Default | Purpose |
| --- | --- | --- |
| `chromellm.native.backend` | `cpu` | `cpu` or `webgpu` artifact |
| `chromellm.native.parallel` | `2` | Maximum parallel native compiler jobs |
| `chromellm.native.configuration` | `Release` | CMake build configuration |
| `chromellm.native.fetch` | `true` | Allow missing fork/dependency source downloads |
| `chromellm.cmake` / `chromellm.ctest` | `cmake` / `ctest` | Explicit tool executables |
| `chromellm.native.toolchain` | empty | Optional CMake toolchain file |
| `chromellm.llama.revision` / `chromellm.dawn.revision` | POM commit pins | Revision for an automatically fetched fork |

The packaged source build uses static engine dependencies where supported,
linked GGML providers, baseline CPU instruction settings, and no OpenMP runtime.
The pre-existing direct CMake route remains available for separately tuned builds.
System runtime and GPU driver libraries remain operating-system dependencies.
Native `verify` includes actual packaged-JAR loading and device enumeration in
fresh JVMs through both classpath and module path. It requires execution on the
matching target system; device enumeration does not execute a trained model.

```sh
mvn -f standalone/pom.xml -Pnative-synthetic verify
```

The separate `native-synthetic` profile builds the existing synthetic JNI fixture,
checks native dependency discovery/relocation, and tests packaged loading. It
does not fetch an engine or attach a native artifact to Maven's install/deploy
outputs. Its manifest is marked synthetic, and loading it requires the explicit
test-only JVM property `-Dchromellm.native.allowSynthetic=true`. These fixtures
prove JNI lifecycle and packaging behavior, not model inference or GPU execution.


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

The source-build option uses linked GGML providers (`GGML_BACKEND_DL=OFF`) and delegates WebGPU configuration to llama.cpp. The CMake source-build path does not download source or models. The independently installed Dawn package and its build prerequisites must already be available.

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

## Bounded coding-agent sessions

Run `--agent true` with `--agent-endpoint` and `--agent-token-env` to use an
explicitly configured headless repository service for source inspection and
candidate previews. See [AGENT.md](AGENT.md) for setup, tools, MCP integration,
budgets and source-hash checks. This mode emits its final result as structured JSON.

For read-only local workspace tools, select `--agent true --workspace /absolute/project`
with the existing model-provider and prompt-file options. `--agent-max-steps` and
`--agent-max-calls` bound this workspace loop. It writes the final answer to stdout
and ordered progress metadata to stderr. Repository-service and workspace controls
are exclusive, and neither mode changes the selected inference provider.

In MCP mode, an operator-configured repository service adds `run_agent`; an
operator-configured `--workspace` adds `agent_run`. The existing `plan_steps`,
`backend_status` and `infer_text` tools remain available. Tool discovery and provider
status do not trigger model acquisition or inference.

## Smart Build diagnostics from AST facts and Maven logs

The existing `smart-build` profile can send a completed build's explicit AST-facts
receipt and log to the configured local CLI or JNI model. It returns a structured,
reviewable diagnostic candidate under the analyzed project's `target/smart-build`.
The existing `promptFile`-only recipe still produces the same plain-text candidate.

Use a recipe inside the project being analyzed. For example, the following
properties extend an existing working native-model recipe. Replace the digest
and UUID placeholders with the identities of the specific completed build:

```properties
promptFile=build-context/question.txt
backend=jni
jniLib=/absolute/path/to/libchromellm_llama_jni.so
model=/absolute/path/to/verified-model.gguf
computeBackend=cpu
gpuLayers=0
contextTokens=8192
maxTokens=1024
timeoutSeconds=120

buildInvocationId=<fresh canonical UUID identifying this build>
buildExitCode=1
buildLogFile=build-context/maven.log
buildLogSha256=<lowercase SHA-256 of the complete log>

# Optional: use the original compiler invocation's exact receipt and UUID.
astFactsFile=target/m3-ast-build-facts/compile-<compiler UUID>.tsv
astFactsSha256=<lowercase SHA-256 of the complete receipt>
astFactsId=<the compiler UUID in that receipt's header>
```

`question.txt` might contain `Explain the compilation failure and suggest the
smallest source correction supported by the evidence.` The question is task data;
the diagnostic schema and read-only boundary remain fixed by Smart Build.

The Maven fork's existing `-Dm3.ast.buildFacts=true` option asks the M3AST javac
producer for `M3AST_BUILD_FACTS_V1` receipts. Their coverage remains `PARTIAL`.
They are not a replacement AST or a complete dependency graph. A compiler failure
can produce no fresh receipt; use log-only diagnostics in that case. Supply an
explicit known invocation rather than selecting the newest file left in `target`.
The digest and UUID checks bind the caller-selected evidence; they do not discover
or independently prove which build produced a caller-supplied file.

Run the profile from the ChromeLLM standalone POM, pointing it at the project:

```sh
mvn -o -f /path/to/ChromeLLM/standalone/pom.xml -Psmart-build \
  -Dchromellm.smart.workspace=/path/to/project \
  -Dchromellm.smart.recipe=build-context/diagnostics.properties verify
```

The default workspace is the standalone project itself. The output directory
defaults to `${chromellm.smart.workspace}/target` and must be that existing,
in-project Maven target directory. All question, recipe, AST and log input paths
must remain inside the analyzed project; evidence paths cannot traverse symlinks.
Local model/native-library paths retain the existing provider's explicit selection.
Relative provider paths resolve against the analyzed project. Maven offline mode
is forwarded to the existing digest-verified model cache.

### Evidence and response contract

| Input | Complete input limit | Model excerpt |
| --- | ---: | --- |
| Task/question | 8 KiB UTF-8 in context mode | Complete text |
| AST facts | 1 MiB | First 8 KiB, preferring complete lines |
| Maven log | 8 MiB | Last 16 KiB, preferring complete lines |
| Complete encoded model request | 64 KiB | Includes instructions, JSON escaping and all evidence |

Each excerpt carries its original SHA-256, 1-based line range, byte offsets and
omitted-byte count. A single line larger than its budget remains a bounded,
UTF-8-safe fragment with an explicit partial-line marker. Inputs are strictly
decoded; mismatched pins, invocation headers, malformed UTF-8, NUL and oversized
inputs fail before the provider opens. The bridge transports the producer's
original TSV text, whose string columns use base64url UTF-8; it does not parse
source files or rebuild compiler facts. Model context capacity remains the
selected provider's responsibility and should match the evidence workload.

Context mode asks for strict JSON such as:

```json
{
  "schema": "CHROMELLM_BUILD_DIAGNOSTICS_V1",
  "summary": "Compilation failed because a referenced type was unavailable.",
  "diagnostics": [
    {
      "severity": "ERROR",
      "evidenceId": "build-log",
      "startLine": 42,
      "endLine": 43,
      "message": "The compiler reports an unresolved symbol.",
      "suggestion": "Check the named import and the module's declared dependency."
    }
  ]
}
```

The real response must reference an evidence ID and line range actually supplied
in that request. At most 32 diagnostics are accepted; unknown fields, invalid
severities, invented references and malformed JSON are refused without publishing
a candidate. A tiny native smoke-test model proves runtime transport, not useful
build reasoning or compliance with this response schema. Use a model qualified
for the diagnostic workload.

Accepted results are immutable `candidate-<request-key>.json` files. The adjacent
receipt binds the recipe, full assembled prompt, context JSON and candidate by
SHA-256 and records the supplied build invocation and exit code. The model's text
is advice: Smart Build does not apply patches, execute suggested commands, bypass
Maven goals or change the recorded exit code. Invoke it after the original build
completes, and preserve that build's exit status in any calling CI wrapper; the
diagnostics invocation has its own success/failure status.
