# ChromeLLM: choose a runtime and follow a request

This fork contains several adjacent systems. Choose the one you need before
installing dependencies or interpreting a test result. The
[README](../README.md) remains the project landing page; existing API and media
guides provide detailed feature contracts.

## Three paths, different prerequisites

| Goal | Starting point | Prerequisites and boundary |
| --- | --- | --- |
| Local Gemini Nano chat API | [OpenAI-compatible API guide](OPENAI_COMPAT_API.md) | Windows PowerShell, Node.js, compatible ChromeML runtime components, and user-supplied compatible model weights |
| Study the diffusion pipeline without an LLM | [Java/native diffusion guide](DIFFUSION_JAVA_NATIVE.md) | JDK 21 and Maven for the Java path; no Gemini Nano weights needed |
| Transform images/video locally | [GPU image/video guide](GPU_IMAGE_VIDEO.md) | Java path plus dependencies of the selected GPU/native/video backend |

The Node/PowerShell chat server does not become portable because Maven compiles
Java. Conversely, the pure-Java diffusion example does not require the Windows
chat server when no `--prompt` is supplied. Optional native builds use CMake
3.20+, a C++17 compiler, and JNI headers. The root
[CMakeLists.txt](../CMakeLists.txt) conditionally enables OpenCV and FFmpeg JNI
targets when their development libraries are found; a successful build can omit
those optional targets.

## Source map

- [runtime/chromeml_api_server.cjs](../runtime/chromeml_api_server.cjs): HTTP
  routes, prompt conversion, parameter validation, request queue, runner process,
  and OpenAI-style responses.
- [启动API服务.ps1](../启动API服务.ps1): finds Node and sets API host/port.
- [运行裸Runner.ps1](../运行裸Runner.ps1): runner invocation wrapper.
- [runtime/ChromeMLBareRunner.cpp](../runtime/ChromeMLBareRunner.cpp): native
  ChromeML runner; ABI-sensitive runtime behavior lives below the HTTP layer.
- [scripts/build_runner.ps1](../scripts/build_runner.ps1): Zig C++ rebuild of
  the Windows executable, separate from Maven/CMake Java/native targets.
- [Java CLI](../src/main/java/io/synexia/chromellm/cli/Main.java): selects a
  diffusion engine, optional scene conditioning, and PPM output.
- [native](../native): diffusion ABI, JNI adapters, GPU and media backends.
- [pom.xml](../pom.xml): Java 21 compilation, JUnit, JNA, and Maven plugins.

## Quick checks before running chat inference

1. Confirm the executable and compatible runtime DLLs described in the README
   are present. Building the executable is not evidence of ABI compatibility.
2. Confirm the required model file exists locally at the versioned path in
   [model/README.md](../model/README.md). Weights are not supplied or licensed by
   this project; do not upload or redistribute them with a contribution.
3. Keep the listener on `127.0.0.1`. The API reference states that the server
   ignores the Authorization header; `Bearer local` is a compatibility value,
   not access control. Do not expose this server through a public tunnel.
4. Keep prompt-debug logging off for private material. The server's debug branch
   logs the prompt and runner arguments.

## Command cheat sheet

Run from the repository root. These examples were checked against the scripts,
POM, and CLI source, but **were not executed** for this guide.

### Windows chat path

```powershell
# Rebuild only if needed, with Zig available. This replaces the local executable.
.\scripts\build_runner.ps1

# Start the local HTTP service in a dedicated terminal.
.\启动API服务.ps1 -HostAddress 127.0.0.1 -Port 11435

# In a second terminal, verify HTTP routing and advertised model metadata.
curl.exe http://127.0.0.1:11435/v1/models
```

`/v1/models` is a static metadata response in the server: success does **not**
prove model loading or inference. After checking prerequisites, use the small
text-completion request in the [API guide](OPENAI_COMPAT_API.md) to exercise the
runner. Exact generated wording is not a deterministic health assertion.

### Java-only diffusion learning path

```bash
mvn test
mvn package
mvn -q exec:java -Dexec.mainClass=io.synexia.chromellm.cli.Main -Dexec.args="--engine java --width 256 --height 256 --steps 40 --seed 7 --output out.ppm"
```

The final command writes `out.ppm`; use an output path you may replace. With no
`--prompt`, `Main` uses `DiffusionCondition.none()`. The bundled implementation
is a DDIM-style scheduler with a lightweight structural denoiser, not a bundled
trained photorealistic image model. An image file demonstrates pipeline output,
not image-generation quality or GPU execution.

For JNA/JNI, follow the native-library loading instructions in the subsystem
guide after building native targets. Passing Java tests alone does not prove a
native backend was loaded or that a GPU was used.

## Worked exploration: trace a text completion

Read `runtime/chromeml_api_server.cjs` in this order:

1. `handleRequest` dispatches `GET /v1/models` and
   `POST /v1/chat/completions`. Other routes return an error; OpenAI-compatible
   does not mean all OpenAI APIs exist here.
2. `handleChatCompletions` reads JSON, requires a nonempty `messages` array, and
   rejects `n` values other than 1. `buildPrompt` converts messages into one
   prompt. The client must resend conversation history for each request.
3. The job maps `max_tokens` (or `max_completion_tokens`) to `maxOutput`, and
   handles context, top-k, and temperature values. Follow the actual conversion
   and validation rather than assuming all SDK parameters are honored.
4. `enqueueRunner` serializes requests through a promise queue. Each job starts
   `powershell.exe`, which invokes the runner wrapper. This is not a persistent
   model-session API with per-client conversational memory.
5. `runRunner` checks process failure/timeout and parses `full_text` output.
   `createCompletionResponse` shapes the result and returns null usage counts.
6. The streaming branch sends SSE chunks from recognized runner output lines,
   then reconciles any remaining full text. Do not infer token accounting or
   remote-service streaming guarantees from the wire format.

The `model` value is echoed in responses; it is not a general multi-model router.
Unsupported tool calls, structured-output semantics, embeddings, and other
endpoints are listed in the API guide. Inspect implementation before relying on
an accepted but unmapped request field.

## Troubleshooting by boundary

| Observation | Check next |
| --- | --- |
| Node cannot be found | Node on PATH and the startup wrapper's resolution logic |
| `/v1/models` works but completions fail | Runner executable, compatible DLLs, local weights, process output, timeout |
| Java CLI unexpectedly tries the chat server | Whether `--prompt` was supplied; it activates the conditioning client |
| Native feature unavailable after CMake succeeds | Configuration messages and whether optional OpenCV/FFmpeg targets were built |
| Video result differs from expected | Selected backend, decode/encode settings, pipeline order, and feature-specific guide |

For advanced media work, continue with [native vision/media](NATIVE_VISION_MEDIA.md),
[VirtualDub-style transforms](VIRTUALDUB_JAVA_PIPELINE.md), and
[media presets/encoding](MEDIA_PRODUCTION_PIPELINE.md). Preserve the project's
[license](../LICENSE), [notice](../NOTICE), and [third-party terms](../THIRD_PARTY.md).
Documentation and successful source compilation do not establish runtime,
model-license, performance, or production-readiness guarantees.
