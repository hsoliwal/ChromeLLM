# Developing the ChromeLLM fork

[Back to the README](../README.md)

## Choose the component first

This fork contains two independent starting points. Building one does not validate
the other.

| Goal | Source entrypoints | Guide |
| --- | --- | --- |
| Call ChromeML through a local HTTP API | [Node server](../runtime/chromeml_api_server.cjs), [C++ runner](../runtime/ChromeMLBareRunner.cpp), [PowerShell launcher](../启动API服务.ps1) | [API contract](OPENAI_COMPAT_API.md) |
| Explore the Java reference diffusion pipeline or native bridges | [Java engines](../src/main/java/io/synexia/chromellm/engine), [native ABI](../native/diffusion_native.h), [native bridges](../src/main/java/io/synexia/chromellm/nativebridge) | [Diffusion guide](DIFFUSION_JAVA_NATIVE.md) |
| Process images and video | [GPU operations](../src/main/java/io/synexia/chromellm/gpu), [video pipeline](../src/main/java/io/synexia/chromellm/video), [presets](../src/main/java/io/synexia/chromellm/preset) | [GPU guide](GPU_IMAGE_VIDEO.md), [native vision/media](NATIVE_VISION_MEDIA.md), [pipeline](VIRTUALDUB_JAVA_PIPELINE.md), [presets and encoding](MEDIA_PRODUCTION_PIPELINE.md) |

The bundled diffusion denoiser is a lightweight structural reference implementation,
not a bundled trained photorealistic image model. The optional ChromeML planner
supplies conditioning; it does not turn Gemini Nano into the diffusion backend.

## Windows ChromeML path

Use the README's [local model instructions](../README.md#add-local-model-file)
and verify compatibility of the runner, Chrome runtime components, and model
version before starting. This path needs Windows, PowerShell, Node.js available
on `PATH`, and the separately obtained compatible model assets.

From the repository root, the checked-in runner build script requires Zig and
writes `runtime/ChromeMLBareRunner.exe`:

```powershell
.\scripts\build_runner.ps1
.\启动API服务.ps1 -HostAddress 127.0.0.1 -Port 11435
```

In another terminal, `curl.exe http://127.0.0.1:11435/v1/models` checks that the
HTTP service responds. It does not prove inference works; use the README's small
chat-completion example for that.

Keep the service on loopback. The server does not enforce API-key authentication;
a Bearer header is not an access-control boundary. Check the
[compatibility matrix](OPENAI_COMPAT_API.md#compatibility-matrix) before adopting
an OpenAI client feature: tool calls, structured outputs, and other OpenAI
endpoints are not implemented.

## Java and native paths

The [Maven configuration](../pom.xml) targets Java 21.
For the Java test suite, run from the repository root:

```sh
mvn -B test
```

The [CMake project](../CMakeLists.txt) requires CMake 3.20+, a C++17 compiler,
and JNI headers from a JDK. The same native build commands appear in
[CI](../.github/workflows/java-native-diffusion.yml):

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release --parallel 2
```

OpenCV and FFmpeg development libraries enable additional JNI targets when
detected; a successful CMake build alone does not mean those optional targets
were built. Consult its configure output and the native media guide. GPU
execution additionally depends on an available OpenCL runtime/device.

The OpenCV and FFmpeg JNI integration tests are opt-in. After building the
optional libraries and installing the FFmpeg executable, the Linux CI invocation
can be reproduced from the repository root:

```sh
LD_LIBRARY_PATH="$PWD/build" mvn -B -Dchromellm.native.integration=true \
  -DargLine="-Djava.library.path=$PWD/build" test
```

Ordinary `mvn test` does not exercise those gated integrations or the Windows
ChromeML inference chain. Report which backend, operating system, dependencies,
and commands you actually tested.

## Changes and provenance

Propose fork-specific changes against
[hsoliwal/ChromeLLM](https://github.com/hsoliwal/ChromeLLM/pulls). Upstream
Chrome2api remains a separate project; this fork's Java/media additions do not
imply upstream support.

Read [LICENSE](../LICENSE), [NOTICE](../NOTICE), and
[THIRD_PARTY.md](../THIRD_PARTY.md) before redistributing artifacts. Project
source, Chromium/Dawn reference headers, runtime binaries, optional native
dependencies, and Google model assets have different provenance. A file's
presence in this tree does not by itself establish redistribution rights.
Never commit model weights, credentials, browser profiles, or private prompts.
