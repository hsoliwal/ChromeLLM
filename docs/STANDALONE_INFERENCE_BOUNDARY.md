<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# ChromeLLM: browser-free standalone inference boundary

**Status:** Implemented browser-removal boundary; local and hosted provider contracts exist. Actual model/GPU/platform qualification is separate from source-level acceptance.

**Product owner:** [hsoliwal/ChromeLLM](https://github.com/hsoliwal/ChromeLLM).
**Canonical Maven/OpenRewrite recipe and provenance owner:** [hsoliwal/com.synexia](https://github.com/hsoliwal/com.synexia).
**Downstream Java receiver:** [hsoliwal/M3jdk21](https://github.com/hsoliwal/M3jdk21), subject to independent tests.

This document describes the current source after [ChromeLLM PRs #84](https://github.com/hsoliwal/ChromeLLM/pull/84), [#85](https://github.com/hsoliwal/ChromeLLM/pull/85), [#95](https://github.com/hsoliwal/ChromeLLM/pull/95) and [Dawn PR #25](https://github.com/hsoliwal/dawn/pull/25). Earlier proposals involving Chrome Prompt/Summarizer APIs, a Node/PowerShell ChromeML runner, Chrome-served chat, and WebMCP page adapters are **retired**. They remain available in Git history for source attribution and audit, **not as runnable instructions or supported providers**.

## Active providers

The [standalone Java 21 module](../standalone/src/main/java/module-info.java) exports `io.synexia.chromellm.standalone` and uses JDK modules `java.base` and `java.net.http`. [StandaloneMain](../standalone/src/main/java/io/synexia/chromellm/standalone/StandaloneMain.java) requires an explicit backend when deviating from the default CLI backend; its provider choices are:

| Selection | Execution boundary | Required inputs |
| --- | --- | --- |
| `--backend cli` (default) | `LlamaCppCliBackend` launches a configured local llama.cpp executable | Executable and lawfully supplied GGUF model |
| `--backend jni` | `LlamaCppJniBackend` uses an explicit JNI library and in-process native llama.cpp | JNI library and licensed GGUF model; optional native Dawn/WebGPU device selection |
| `--backend gemini` | `GeminiApiBackend` uses the hosted Gemini API through Java HTTP | Explicit API credentials and network access; **not** offline |

`--backend chrome` and `--backend bridge` are rejected. The removed Chrome executable/profile/DevTools/ChromeML options do not belong to any active provider. No silent fallback from one provider to another, implicit browser launch, or unapproved model substitution is permitted.

The local MCP **stdio** server, agent and workspace tools are process integrations, not browser WebMCP. The separate root [media/diffusion project](../pom.xml) retains its Java/JNA/JNI, OpenCL, OpenCV and FFmpeg functionality without a Chrome dependency. Neither a Java-only build nor a synthetic native test establishes correct trained-model inference.

## Dependency and ownership direction

```text
CLI / embedded Java API / bounded MCP stdio / configured agent
                              |
                        ModelBackend
                         /    |    \
                llama CLI  llama JNI  Gemini API
                    |         |           |
             licensed GGUF  C++/JNI    Java HTTPS
                              |
                      optional native
                       Dawn/WebGPU
```

The native Dawn fork implements the WebGPU C/C++ API for local GPU backends. **WebGPU, WGSL and Tint are not browser dependencies.** The browser-specific Dawn queue-copy extension was removed; standard native texture upload, command-encoder copies and external-texture support are intentionally retained.

## Contract, source seals and admission

The existing request/response contract is [ModelRequest](../standalone/src/main/java/io/synexia/chromellm/standalone/ModelRequest.java), [ModelResponse](../standalone/src/main/java/io/synexia/chromellm/standalone/ModelResponse.java) and [ModelBackend](../standalone/src/main/java/io/synexia/chromellm/standalone/ModelBackend.java). Work involving code generation and M3JDK21 receipt promotion must additionally preserve:

- Explicit provider, model, runtime and compute-device identities; distinguish requested GPU execution from measured device execution.
- Immutable source path and SHA-256 (or an explicitly checked `ABSENT` state), frozen Java interface/LLD digest, recipe identity, provenance and license status.
- Bounded prompt/input/output, deadlines, cancellation and cleanup; deterministic refusal on unsupported capabilities, drift, invalid schemas and partial results.
- The existing Synexia **one-generation-call per sealed file candidate** rule. A new deliberate request can start a new run; failed or partial output does not authorize hidden retry, source writes or provider fallback.
- Code generation outputs remain `HOLD_UNVERIFIED` and non-promotable until canonical Maven/OpenRewrite recipe execution, drift/fixed-point verification, compile/JUnit/JNI/ABI behavior tests and recipient-gated review.

Model inference is candidate data; it never grants shell/file-write authority. Model artifact caching must verify source, revision, digest, size, timeout and license conditions. Do not copy or redistribute Chrome-owned model weights, proprietary ChromeML binaries, browser profiles, cookies or credentials.

## Qualification matrix

| Gate | What is required | Boundary of current claims |
| --- | --- | --- |
| Java module | Java 21 compilation, provider-selection contracts, `jdeps` closure | Module source and CI exist; check actual workflow conclusions |
| Browser elimination | No retired runtime files, browser providers/options, Node/Chromium compile dependencies or WebMCP page adapter | Deleted source and negative CI gates; historical documentation is preserved through Git |
| Native JNI | JNI symbol and lifecycle tests, bounded requests, native library loading/cleanup | Synthetic tests do not prove real GGUF inference |
| Real licensed model | Exactly pinned model/runtime, actual inference, recorded output, deterministic repeat | Only an executed real-model workflow result can establish this |
| GPU | Measured native Dawn/WebGPU execution, device/driver evidence and memory behavior | A successfully configured GPU backend is not a device-execution proof |
| Platform | Windows/Linux/macOS executable evidence, optional Android where supported | A source checkout does not imply cross-platform parity |
| M3JDK21 receiver | Reusable Synexia recipes, donor license/source hashes, receiver-side behavioral/ABI tests | No automatic promotion based on a ChromeLLM or Dawn merge |

Verify claims against the exact [standalone CI workflow](https://github.com/hsoliwal/ChromeLLM/actions/workflows/standalone-core.yml) and [native Dawn CI](https://github.com/hsoliwal/dawn/actions/workflows/native-source-boundary.yml); never interpret queued checks as passes.

## Maintained entry points

Use [START_HERE](START_HERE.md), the [current architecture](ARCHITECTURE_AND_PROJECTS.md), [standalone README](../standalone/README.md) and [ADR-0001](ADR-0001-BROWSER-INDEPENDENCE.md). The original draft protocol and ChromeML process-split milestones remain in the preceding revisions of this document for provenance only. Do not revive an abandoned browser route as an optional provider without a new explicit architectural decision.
