# ChromeLLM — standalone Java/JNI inference with Hydrogen and WebGPU

ChromeLLM now separates its independent Java 21 model runtime from Chrome2api/ChromeML. **The source product no longer requires browser APIs, Node/PowerShell runners, ChromeML/Dawn DLLs or Chrome-managed model weights for text inference.** Historical fork attribution remains in LICENSE, NOTICE, THIRD_PARTY.md and Git history.

- [standalone/](standalone/README.md) is a separately built Java 21 module using only `java.base` and `java.net.http`. Select local llama.cpp through CLI/JNI or **Gemini 4 Hydrogen through the Gemini API**. Native inference can explicitly select the existing **Dawn/WebGPU provider in llama.cpp**.
- `src/main/java/io/synexia/chromellm/` owns separate diffusion/GPU/vision/video/media functionality; the diffusion CLI no longer calls Chrome.
- `native/` and the root CMake build contain media JNI adapters; `standalone/native/` contains the independent llama.cpp JNI library with a test-only synthetic backend.

Run `mvn -f standalone/pom.xml verify` to compile the module and run its local-process, loopback HTTP and provider-selection contract tests. No API key is required for these tests.

| Selection | Execution | Required input |
| --- | --- | --- |
| `--backend cli` (default) | Local llama.cpp process | Executable and licensed GGUF model |
| `--backend jni` | Local in-process llama.cpp | JNI library and licensed GGUF model |
| `--backend jni --compute-backend webgpu` | Local llama.cpp with its Dawn/WebGPU provider | WebGPU-enabled native build and positive `--gpu-layers` |
| `--backend gemini` | Hosted Gemini API, default `gemini-4-hydrogen` | `GEMINI_API_KEY` environment variable |

For Hydrogen, after setting `GEMINI_API_KEY` in the process environment:

```sh
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --backend gemini --thinking-level high --prompt-file /path/to/request.txt
```

Provider selection is explicit. Local inference never switches to a remote API, and a missing WebGPU provider is an error when WebGPU was requested. The Hydrogen adapter uses Google's hosted model; Dawn accelerates independently supplied local models. See the [standalone README](standalone/README.md) for builds, options, limits and verification commands.

A passing synthetic JNI test proves binding/lifecycle behavior only, not trained model correctness, GPU acceleration, or OS qualification. Older ChromeML documentation is historical. Transform recipes and fixed-point source qualification are owned by hsoliwal/com.synexia; downstream M3jdk21 imports only separately qualified implementation outputs.
