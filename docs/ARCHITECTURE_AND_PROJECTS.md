# Architecture and project boundaries

This page describes the **current, browser-free** [ChromeLLM fork](https://github.com/hsoliwal/ChromeLLM). It supersedes the 2026-10-30 architecture inventory, which described a Node/PowerShell/ChromeML runner and optional installed-Chrome provider that have since been deleted. The prior inventory remains available in Git history and the [Synexia browser-removal provenance](https://github.com/hsoliwal/com.synexia/tree/develop/synexia-openrewrite-recipes/recipe-crates/browser-removal-20261117).

[Start here](START_HERE.md) · [Standalone build](../standalone/README.md) · [Native contracts](NATIVE_RUNTIME_CONTRACTS.md) · [Java packages](JAVA_PACKAGE_CONTRACTS.md) · [Media lifecycle](MEDIA_PIPELINE_CONTRACTS.md)

## Current build boundaries

These are separate build entry points, **not** a single Maven reactor.

| Component | Source and build owner | Responsibility |
| --- | --- | --- |
| Java 21 standalone inference | [standalone/pom.xml](../standalone/pom.xml), [module-info.java](../standalone/src/main/java/module-info.java) | JPMS module `io.synexia.chromellm.standalone`; requires `java.base` and `java.net.http`; contains provider selection, bounded protocols, model acquisition and agent orchestration |
| Local inference providers | [standalone/src/main/java/io/synexia/chromellm/standalone/](../standalone/src/main/java/io/synexia/chromellm/standalone/) | Explicit `LlamaCppCliBackend` or `LlamaCppJniBackend`, using a licensed, separately supplied GGUF model |
| Optional hosted inference | [GeminiApiBackend.java](../standalone/src/main/java/io/synexia/chromellm/standalone/GeminiApiBackend.java) | Explicit Gemini API request over Java HTTP; not a Chrome-installed or browser model provider |
| Local agent and MCP stdio | [McpStdioServer.java](../standalone/src/main/java/io/synexia/chromellm/standalone/McpStdioServer.java), [AgentSession.java](../standalone/src/main/java/io/synexia/chromellm/standalone/AgentSession.java) | Bounded, locally executed stdio protocol and configured agent tools; **not** a WebMCP page/browser adapter |
| In-process JNI and packaging | [standalone/native/](../standalone/native/) | llama.cpp C/C++ JNI bridge, native library discovery, source/binary packaging and synthetic native tests |
| Pinned native source superbuild | [standalone/native-superbuild/CMakeLists.txt](../standalone/native-superbuild/CMakeLists.txt), [standalone/pom.xml](../standalone/pom.xml) | License-reviewed, revision-pinned llama.cpp and optional **native** Dawn/WebGPU input; Chromium, Emscripten and Node browser targets are not required |
| Java diffusion, image, vision, video | [root pom.xml](../pom.xml), [src/main/java/](../src/main/java/) | Independent Java media library, CLI, scheduling, transformations, pipeline and GPU/vision adapters |
| Native media adapters | [root CMakeLists.txt](../CMakeLists.txt), [native/](../native/) | Optional native diffusion, OpenCL, OpenCV and FFmpeg/JNI bridges; media lifecycle is separate from standalone language-model inference |

The root Maven media project and standalone inference module have different artifact identities. A passing build of one does not establish the other's runtime or GPU correctness.

## Inference dependency direction

```text
StandaloneMain / embedded ModelBackend / bounded MCP stdio
                         |
                  ModelRequest / ModelResponse
                         |
                  explicit --backend
                    /      |       \
              llama CLI   JNI   Gemini API
                 |        |        |
           local GGUF   llama.cpp  HTTPS
                          | 
                     optional native
                       Dawn/WebGPU
```

Browser integration is **not** an allowed execution route. The removed `runtime/`, `reference/`, `webmcp/`, installed-Chrome provider, Chrome DevTools adapter and Chrome-served chat bridge are not active project boundaries. Historical source and third-party license notices remain in Git history rather than being repackaged as runnable examples.

The name `ChromeLLM` is the product/repository identity, not a dependency on Chrome. Similarly, WebGPU is a portable native GPU API: using Dawn's native WebGPU implementation does not invoke a web browser. `McpStdioServer` is a stdio transport, not browser WebMCP.

## Product and ownership invariants

- Java owns the externally exposed request, response and lifecycle contracts. Native JNI/C++ owns selected inference and GPU handles, with explicit cleanup and deterministic failure behavior.
- The Java standalone module does not require an installed Chrome, Node, ChromeML or browser-managed model assets.
- A local model, native runtime or hosted API is selected explicitly. Model acquisition must honor source pinning, checksum, licensing, size and timeout constraints. No hidden browser fallback is permitted.
- Standard Dawn/WebGPU native functionality remains available; the browser-only Dawn `CopyTextureForBrowser` queue extension was removed in [Dawn PR #25](https://github.com/hsoliwal/dawn/pull/25).
- Source/provenance, recipes and qualification evidence belong in [com.synexia](https://github.com/hsoliwal/com.synexia); this product repository retains normal source, tests and user-facing build instructions.
- Preserve upstream notices in [LICENSE](../LICENSE), [NOTICE](../NOTICE) and [THIRD_PARTY.md](../THIRD_PARTY.md).

## Validation and limits

[Standalone CI](https://github.com/hsoliwal/ChromeLLM/actions/workflows/standalone-core.yml) runs Java 21 provider/agent contracts, module-closure checks, synthetic JNI work, native build and real-model jobs. [Java/native media CI](https://github.com/hsoliwal/ChromeLLM/actions/workflows/java-native-diffusion.yml) checks the separate media pipeline. Running a synthetic backend or compiling a native library does **not** prove trained model correctness, actual GPU acceleration, driver support or cross-platform parity; consult the specific workflow run's conclusion and artifacts.

The browser removal history is reviewable in [ChromeLLM PR #84](https://github.com/hsoliwal/ChromeLLM/pull/84), [PR #85](https://github.com/hsoliwal/ChromeLLM/pull/85), [PR #95](https://github.com/hsoliwal/ChromeLLM/pull/95), and [Dawn PR #25](https://github.com/hsoliwal/dawn/pull/25). The 2026-10-30 ChromeML/Node execution topology is obsolete; do not use those old commands to configure this fork.
