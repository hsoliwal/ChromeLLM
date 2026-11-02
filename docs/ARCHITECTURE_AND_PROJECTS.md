# Architecture and project boundaries

> **Historical snapshot, not a runnable Chrome integration:** ChromeLLM draft PR #19 removes the old ChromeML/Dawn, Node and PowerShell execution files. Current text inference uses a separate llama.cpp JNI backend (see [START_HERE](START_HERE.md)). The legacy architectures, links and verification counts below are retained solely for provenance; do not follow them as build instructions. Real GGUF inference remains to be qualified.

[Development entry points](FORK_DEVELOPMENT.md) · [Java packages](JAVA_PACKAGE_CONTRACTS.md) · [Media lifecycle](MEDIA_PIPELINE_CONTRACTS.md) · [Native and HTTP contracts](NATIVE_RUNTIME_CONTRACTS.md) · [Coverage ledger](DOCUMENTATION_COVERAGE.md)

This guide describes the public fork at source snapshot `26473e370c6d7cedace4a0b21e8ad56a35ff2dfc`. Its implementation is unchanged from the documentation branch's base `11d834102a56c1f8db2f7b658a233c212a62ac18`. Statements below are source observations, not execution results. The fork relationship is [hsoliwal/ChromeLLM](https://github.com/hsoliwal/ChromeLLM) → [xszwow/Chrome2api](https://github.com/xszwow/Chrome2api).

## Six independently configured units

These are project boundaries, not six Maven reactor children. There is no root Maven `<modules>` list, Java `module-info.java`, or Node `package.json` in this snapshot.

| Project identity | Boundary evidence and artifact | Responsibility and dependencies |
| --- | --- | --- |
| Java diffusion/media library and CLIs | [pom.xml](../pom.xml), `io.synexia:chromellm-diffusion:1.0.0-SNAPSHOT`; Maven's default JAR packaging | Java 21; JNA 5.17.0, Jackson 2.20.0, JUnit 5.13.4. Owns the 14 main Java namespaces and six test namespaces below. Its two executable entry points are `cli.Main` and `gpu.cli.GpuMain`; the POM does not create a shaded executable JAR |
| Native bridge build | [CMakeLists.txt](../CMakeLists.txt), project `chromellm_diffusion` | C++17, CMake 3.20+, required JNI headers. Six shared-library targets provide diffusion, OpenCL, OpenCV, and FFmpeg interfaces. Optional target detection is separate from Java compilation |
| Windows ChromeML runner | [scripts/build_runner.ps1](../scripts/build_runner.ps1), [runtime/ChromeMLBareRunner.cpp](../runtime/ChromeMLBareRunner.cpp) | Zig cross-targets `x86_64-windows-gnu`, links Windows libraries, writes `runtime/ChromeMLBareRunner.exe`. The [launcher](../运行裸Runner.ps1) selects runtime DLLs/model weights and invokes the runner |
| Node HTTP adapter | [runtime/chromeml_api_server.cjs](../runtime/chromeml_api_server.cjs), [启动API服务.ps1](../启动API服务.ps1) | CommonJS entry point using Node built-ins, starts an HTTP listener and serializes calls to the Windows launcher. No npm install/build script is declared here |
| Hierarchy intake overlay | [.m3/atom-pattern/pom.xml](../.m3/atom-pattern/pom.xml), `com.synexia.intake:repository-hierarchy-intake:1` | Separate POM-only analysis configuration, delegates to an externally installed recipe artifact; does not reparent the application POM |
| Content-bound analysis runner | [.m3/atomize-patternize](../.m3/atomize-patternize) | Bash bootstrap/run entry points with source-content inventory and external Maven/OpenRewrite prerequisites; writes analysis output, not an application artifact |

## Dependency direction and ownership

The Java diffusion path is `cli.Main` → optional `chrome.ChromeAiConditioningClient` → `api.DiffusionCondition` → `engine.DiffusionEngine`. Engine choices are the pure-Java reference, JNA C ABI, and JNI adapter. The planner sends a prompt to the local chat API to derive numeric conditioning. It does not supply trained diffusion weights. Java and native reference engines use different random-number implementations; identical seeds are not a cross-backend pixel-equivalence guarantee.

The media path is `gpu.cli.GpuMain` → `preset`/`video`/`batch` → `gpu.ImageProcessor` or `vision.VisionProcessor`. `gpu` owns copied RGBA buffers; `video` owns frame ordering, stream shape, cancellation and subprocess resources; `preset` compiles configuration into a transform chain; `batch` discovers files and collects per-item failures. Native processors and frame sources own handles; callers that open them must close them. A transform borrowing a processor does not acquire or close that processor.

The Node server → PowerShell → C++ runner → ChromeML/Dawn path is independent of Maven and the CMake media libraries. The Java planner is an optional client of this server, not its in-process implementation. A Java/media CI pass cannot establish compatibility of a user's ChromeML binary/model pair.

## Native build modules

| Target | Sources | Dependencies and output boundary |
| --- | --- | --- |
| `chromellm_diffusion` | [diffusion_native.cpp](../native/diffusion_native.cpp), [C ABI header](../native/diffusion_native.h) | Reference diffusion core; exported `diffusion_*` functions |
| `chromellm_diffusion_jni` | [diffusion_jni.cpp](../native/diffusion_jni.cpp) | JNI → `chromellm_diffusion`; Java array conversion and error translation |
| `chromellm_gpu` | [gpu_opencl.cpp](../native/gpu_opencl.cpp), [C ABI header](../native/gpu_opencl.h) | Loads the OS OpenCL runtime dynamically; links `${CMAKE_DL_LIBS}` when applicable; builds kernels at processor creation |
| `chromellm_gpu_jni` | [gpu_jni.cpp](../native/gpu_jni.cpp) | JNI → `chromellm_gpu`; validates RGBA sizes and wraps handle calls |
| `chromellm_opencv_jni` | [opencv_jni.cpp](../native/opencv_jni.cpp) | Conditional on OpenCV `core`, `imgproc`, `photo`, `video`; no target is created when discovery fails |
| `chromellm_ffmpeg_jni` | [ffmpeg_decoder_jni.cpp](../native/ffmpeg_decoder_jni.cpp) | Conditional on pkg-config finding libavformat/libavcodec/libavutil/libswscale; direct decoding, not encoding |

The Java artifact and two runtime entry points are also module-level units in the coverage ledger. The two `.m3` configurations are tooling modules. This yields 11 explicit build/runtime/tooling modules; Java namespaces are counted separately as packages rather than pretending each directory is a JPMS module.

## Repository areas that are not new packages

- `data/` contains image/audio fixtures, not a Java resource module
- `model/` contains setup guidance and a placeholder for externally obtained weights; the required weight file is not in the tree
- `reference/` contains Chromium reference source/headers; `runtime/tag_148_headers/` and the generated `.inc` files are ABI/generated support material, not independently authored Java APIs
- `runtime/` also contains a tracked EXE and two DLLs at this snapshot. The “Not Included” section of [THIRD_PARTY.md](../THIRD_PARTY.md) says runtime binaries are not included, so there is a source-tree/documentation discrepancy. Preserve notices and resolve provenance before redistribution; this guide grants no rights and does not modify the notice
- `docs/` contains user guides and a preset JSON example; it is not a configured documentation-site build
- `.github/workflows/` configures existing Java/native CI. No new workflow, check, or integration test is introduced by these guides

## Tooling overlays and their side effects

The [hierarchy intake POM](../.m3/atom-pattern/pom.xml) binds `exec:java` to `verify`, runs `com.synexia.ai.m3scale.M3HierarchyMain`, and passes repository root, output location, repository identity, revision, and `--git`. The recipe artifact implementation is external to this checkout. Its README requires an independently installed artifact and an output directory outside this repository. Reading that declaration is not evidence that the recipe ran.

The [bootstrap script](../.m3/atomize-patternize/bootstrap.sh) refuses to replace an existing inventory unless `SYNEXIA_REBIND_INVENTORY=1`. It scans ordinary Java/POM files, skips listed build/vendor directories, hashes each file twice, and writes `inventory.tsv` plus transient files beneath `.m3/target/atomize-patternize`. The [run script](../.m3/atomize-patternize/run.sh) checks bound counts/hashes, requires `SYNEXIA_RECIPE_VERSION` when Java exists, and invokes `rewrite:dryRunNoFork` with external recipes. `SYNEXIA_ATOM_PATTERN_RECIPE` and `SYNEXIA_REWRITE_PLUGIN_VERSION` select recipe/plugin versions. The pinned tree has no bound `inventory.tsv`; invoking `run.sh` immediately would hit `M3_PLAN_INVENTORY_REQUIRED`. These scripts are described but were not executed.

## Build and verification navigation

Use [FORK_DEVELOPMENT.md](FORK_DEVELOPMENT.md) for the exact Maven, CMake, native-integration, and Windows commands. Maven tests use the classpath (`useModulePath=false`). The existing workflow runs Java tests, native builds on Linux and Windows, and the opt-in OpenCV/FFmpeg tests on Linux. It does not test OpenCL device execution or Windows model inference. [Coverage and remaining checks](DOCUMENTATION_COVERAGE.md) distinguishes inspected test bodies from executed results.

Keep [LICENSE](../LICENSE), [NOTICE](../NOTICE), and [THIRD_PARTY.md](../THIRD_PARTY.md) with their original scope and attribution. These source-contract pages neither replace those documents nor infer ownership from fork status.
