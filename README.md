# ChromeLLM — standalone Java/JNI inference and optional Chrome AI

ChromeLLM provides an independent Java 21 model runtime with explicit local CLI/JNI and hosted Gemini providers. It also offers an optional adapter to an installed Chrome browser's built-in AI. Historical fork attribution remains in LICENSE, NOTICE, THIRD_PARTY.md and Git history.

- [standalone/](standalone/README.md) is a separately built JDK-only Java 21 module using `java.base`, `java.net.http` and `jdk.httpserver`. Select local llama.cpp through CLI/JNI, **Gemini 4 Hydrogen through the Gemini API**, or **installed Chrome built-in AI**. Native inference can explicitly select the existing **Dawn/WebGPU provider in llama.cpp**.
- [webmcp/](webmcp/README.md) is an optional browser-page WebMCP tool adapter. It uses injected, user-authorized callbacks. The standalone MCP JSON-RPC stdio server and browser WebMCP are distinct protocols.
- `src/main/java/io/synexia/chromellm/` owns separate diffusion/GPU/vision/video/media functionality; the diffusion CLI no longer calls Chrome.
- `native/` and the root CMake build contain media JNI adapters; `standalone/native/` contains the independent llama.cpp JNI library with a test-only synthetic backend.

Run `mvn -f standalone/pom.xml verify` with JDK 21, Maven and Node.js 22 to compile the module and run its local-process, loopback HTTP, protocol, browser-script and provider-selection contract tests. Node executes the fixed script test harness only; the Java product has no Node runtime dependency. These tests require no API key, installed browser or downloaded model.

**Fork-native Maven smart build:** `-Pfork-native` verifies an explicitly pinned
local `synexia-llmcpp` source checkout and compiles the JNI bridge through CMake,
with optional separately installed Dawn/WebGPU. `-Psmart-build` runs an explicit
Java 21 local-inference recipe during Maven `verify` and writes a hash-bound
candidate only under `standalone/target/smart-build/`. Neither profile requires
or launches Chrome. The ordinary Maven build remains unchanged. See
[the fork-native build contract](standalone/FORK_NATIVE_SMART_BUILD.md).

| Selection | Execution | Required input |
| --- | --- | --- |
| `--backend cli` (default) | Local llama.cpp process | Executable and licensed GGUF model |
| `--backend jni` | Local in-process llama.cpp | JNI library and licensed GGUF model |
| `--backend jni --compute-backend webgpu` | Local llama.cpp with its Dawn/WebGPU provider | WebGPU-enabled native build and positive `--gpu-layers` |
| `--backend gemini` | Hosted Gemini API, default `gemini-4-hydrogen` | `GEMINI_API_KEY` environment variable |
| `--backend chrome` | Installed Chrome's `LanguageModel` API through an owned localhost page | Eligible Chrome installation and a dedicated ChromeLLM profile |

For Hydrogen, after setting `GEMINI_API_KEY` in the process environment:

```sh
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --backend gemini --thinking-level high --prompt-file /path/to/request.txt
```

For installed Chrome, `mvn -f standalone/pom.xml -Pchrome-status verify` checks availability without preparing a model. Set `CHROME_BIN` if Chrome is not in a standard installation path. The separate `chrome-prepare` and `chrome-infer` profiles support explicit provisioning and prompt-file inference. The [standalone README](standalone/README.md#installed-chrome-built-in-ai) documents profile ownership, setup, deadlines, token admission and the trusted self-hosted GitHub runner lane.

Provider selection is explicit. Local inference never switches to a remote API, and a missing WebGPU provider is an error when WebGPU was requested. The Hydrogen adapter uses Google's hosted model; Dawn accelerates independently supplied local models. The Chrome adapter uses the selected browser's own model API and does not extract its weights. See the [standalone README](standalone/README.md) for builds, options, limits and verification commands.

A passing synthetic JNI test proves binding/lifecycle behavior only, not trained model correctness, GPU acceleration, or OS qualification. A portable Chrome fixture pass does not prove live model eligibility or inference. Older ChromeML documentation is historical. Transform recipes and fixed-point source qualification are owned by hsoliwal/com.synexia; downstream M3jdk21 imports only separately qualified implementation outputs. The optional Chrome adapter's Apache-2.0 attribution is in [standalone/NOTICE.md](standalone/NOTICE.md).
