# ADR-0001: Browser-free ChromeLLM is an independent Java/JNI product

- Decision date: 2026-10-30; **reconciled with the implemented removals on 2026-11-18**
- Status: **Accepted — browser runtime and adapters removed**
- Scope: `hsoliwal/ChromeLLM`, native Dawn fork dependency, and qualified downstream receivers
- Historical note: The initial ADR considered optional Chrome Prompt/ChromeML adapters and future JNI. Those alternatives are **retired**; source history and donor attribution remain in Git history.

## Decision

**ChromeLLM must build and run its standalone Java/JNI inference without Chrome, Chromium, ChromeML, a browser profile, a Node/PowerShell Chrome bridge, WebMCP page integration, or Chrome-owned model assets.** The old installed-Chrome provider, Chrome-served chat bridge and WebMCP adapter were deleted, not merely hidden behind defaults.

The permitted standalone providers are explicitly selected:
1. `--backend cli` — local, externally supplied `llama.cpp` executable and licensed GGUF;
2. `--backend jni` — in-process llama.cpp JNI with a licensed GGUF, optionally accelerated through **native Dawn/WebGPU**;
3. `--backend gemini` — an explicitly requested, authenticated hosted API call over Java HTTP.

There is no `--backend chrome` or `--backend bridge` fallback. MCP **stdio** remains supported for local process interoperability, and is distinct from removed browser WebMCP. The media/diffusion Java and JNI project is an independent build boundary.

## Runtime dependency flow

```text
Java 21 StandaloneMain / ModelBackend / MCP stdio
                       |
                selected backend
              /         |         \
         llama CLI   llama JNI   Gemini HTTP API
             |          |
      licensed GGUF   local C++ runtime
                        |
                   optional native
                    Dawn/WebGPU
```

The standalone Java module exports `io.synexia.chromellm.standalone` and uses JDK modules `java.base` and `java.net.http`. No third-party Java runtime JAR or installed browser is required by the Java module. A native library or locally licensed model is still required **when that particular local backend is selected**. The hosted Gemini backend sends requests to the external service and is not an offline provider.

Chrome/Chromium may be researched as **licensed and attributed donors**, but no browser execution path is admitted into this product. The Dawn fork remains useful as a native C/C++ WebGPU implementation; it does not imply a browser requirement. Native WebGPU and shader translation must not be deleted merely because upstream Dawn also ships with Chromium.

## Enforced boundaries

- Build: refuse browser/Emscripten targets; exclude retired Node, V8, CDP, WebMCP and ChromeML runtime directories and dependencies.
- API: refuse removed Chrome/bridge provider choices and their CLI options; retain CLI/JNI/Gemini selection without hidden fallback.
- Source: preserve `LlamaCppCliBackend`, `LlamaCppJniBackend`, `GeminiApiBackend`, `McpStdioServer`, and the independent media/JNI implementation.
- Native: preserve standard WebGPU command encoder and texture APIs. The Dawn-specific browser queue copy extension was deleted in [Dawn PR #25](https://github.com/hsoliwal/dawn/pull/25); generic native color conversion must use explicit pipelines.
- Provenance: exact source revisions, hashes, licenses and downstream derivation metadata must remain traceable. Canonical Maven/OpenRewrite recipes and proof material live in [com.synexia](https://github.com/hsoliwal/com.synexia), not duplicate product tooling.
- Safety: operator-selected model artifacts must be licensed and integrity checked. Do not extract or distribute Chrome-owned weights, ChromeML binaries, browser profiles or credentials.

## Acceptance evidence and limits

The Java contracts exercise backend rejection, bounded input, provider selection and local/stdio behavior. The native suite includes synthetic JNI and optional real-source/model testing. Check the actual [standalone workflow](https://github.com/hsoliwal/ChromeLLM/actions/workflows/standalone-core.yml) and [Dawn native workflow](https://github.com/hsoliwal/dawn/actions/workflows/native-source-boundary.yml) for **executed** results; a queued run, synthetic pass or source-level review is not proof of real GPU/driver/model qualification.

No claim is made that all operating systems, GPU backends, models or downstream M3JDK21 receiver contracts have passed. Promotion requires real tests, source identity checks, and reviewed donor/recipe provenance.

## Reviewable removal history

- [ChromeLLM PR #84](https://github.com/hsoliwal/ChromeLLM/pull/84): installed-Chrome, DevTools and browser WebMCP removal
- [ChromeLLM PR #85](https://github.com/hsoliwal/ChromeLLM/pull/85): retire Chrome-served chat bridge
- [ChromeLLM PR #95](https://github.com/hsoliwal/ChromeLLM/pull/95): prevent browser provider reintroduction
- [ChromeLLM PR #96](https://github.com/hsoliwal/ChromeLLM/pull/96): pin the cleaned native Dawn dependency
- [Dawn PR #25](https://github.com/hsoliwal/dawn/pull/25): remove browser-only texture queue API and tests

See [current architecture](ARCHITECTURE_AND_PROJECTS.md), [build entry points](START_HERE.md) and [standalone build details](../standalone/README.md). For the original October 30 options and deferred milestones, consult this file's previous Git revision; they are **historical design proposals, not supported run paths**.
