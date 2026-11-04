# ADR-0001: ChromeLLM is an independent product; Chrome is an optional donor

> **Historical architectural decision (2026-10-30).** The browser-independent
> Java 21/C++17 in-process JNI provider is implemented on draft ChromeLLM
> PR #20, with pinned independent `llama.cpp` and a licensed GGUF model.
> Legacy Chrome Prompt API, ChromeML/Dawn and optional-browser provider diagrams
> below document alternatives considered in the original decision; they are
> **not** active dependencies or supported product launch instructions.
> For the actual build and inference commands use `standalone/README.md`.
> Release remains qualified separately from source-level proof.

- Status: **Accepted architectural direction; implementation in progress**
- Scope: `hsoliwal/ChromeLLM` and subsequent qualified M3JDK21 receiving work
- Date: 2026-10-30
- Implementation starting point: `standalone/` (draft PR #15)

## Decision

**The ChromeLLM product core MUST NOT depend on the Chrome browser, Chrome
Prompt/Summarizer APIs, ChromeML/Dawn libraries, a Chrome installation/profile,
or Google-owned model assets.** An optional Chrome adapter may depend on those
resources, but removing the adapter must leave the standalone model runtime,
core API and local tests buildable and runnable.

We deliberately distinguish:

1. **Browser API provider** — runs through the host browser's supported
   built-in AI API. Requires browser capabilities and permissions.
2. **ChromeML native provider** — can run without a browser UI/process, but
   still depends on compatible ChromeML/Dawn binaries and model assets. It
   does **not** prove vendor-runtime independence.
3. **Independent local provider** — has neither a browser dependency nor
   a ChromeML/Dawn dependency. The initial `standalone/` adapter invokes an
   externally installed `llama.cpp` CLI with a lawfully supplied GGUF model.
4. **Future in-process native provider** — a stable JNI/C ABI over an independently
   licensed inference implementation, with CPU-first correctness and optional
   accelerator backends. Not delivered by the initial adapter.

Chrome and Chromium remain useful **donors of interface ideas, algorithms and
capability contracts**, subject to license/provenance review; they are not the
permanent execution host or product identity.

## Architecture and dependency direction

```text
CLI / local HTTP / future desktop / M3 clients
                  |
            Core Java 21 API
      request / candidate response / lifecycle
                  |
            ModelBackend port
                  |
       +----------+-----------+---------------+
       |                      |               |
  llama.cpp CLI          future JNI      optional Chrome
 (browserless)          native engine       adapters
       |                      |               |
 external runtime       stable C ABI      browser API OR
 + GGUF model           CPU / GPU / APU    ChromeML assets
```

The arrow points from consumers to contracts to providers. **Core must never
import an adapter or dynamically assume it exists.** A consumer selects a
provider explicitly; there is no hidden Chrome/ChromeML fallback or model
download on initial startup. A model-generated answer is candidate data, not
authority for code changes or a summary's facts.

### Mandatory architecture invariants

- The core is JDK 21 compatible and separately buildable. `java.base` is the
  current sole runtime module of `standalone/`; its Maven module has no
  third-party runtime JAR dependencies.
- Core contracts are provider-neutral (request, response, capability, lifecycle,
  cancellation, failure, evidence). Existing `ModelRequest`,
  `ModelResponse` and `ModelBackend` are the initial narrow slice; capabilities,
  streaming, asynchronous cancellation and receipts need future versioned work.
- Java owns public contracts and orchestration; native code owns independent
  inference, memory/GPU dispatch and resource handles when selected. JNI is
  optional and must have deterministic cleanup and explicit versioned ABI
  negotiation. Never require JNI merely to instantiate/use the Java API.
- Chrome-specific adapter source, model registration and runtime APIs must live
  outside the standalone core. No transitive Chrome dependency is permitted.
- The product may ship without Chrome and without a GPU. CPU execution remains
  a first-class acceptance path. GPU speedups require measured evidence, not
  automatic behavioral equivalence claims.
- External engines/models must be explicit operator-provided, source-pinned,
  license/provenance-reviewed inputs. Do not extract, bundle or redistribute
  Google/Chrome weights or opaque runtime binaries without authorization.
- The pre-existing `runtime/` Chrome2api implementation and Java media/diffusion
  projects remain supported as distinct subprojects. They are not silently
  redefined as standalone inference implementations.
- Every generated/refactored source change passes contract preservation, diff,
  compilation, tests and receipt review. Shared transformations belong to
  reusable, tested Synexia Maven/OpenRewrite recipes, not one-off bulk edits.
  No rebase/squash/force-push of the established evidence chain.

## Required acceptance tests / release gates

| Gate | Evidence required | Initial state |
| ---- | ----------------- | ------------- |
| Java runtime closure | `jdeps` reports `java.base` only for `standalone/` | CI assertion included |
| Core dependency boundary | No browser imports or ChromeML required to compile/run synthetic provider tests | JDK-only contract test included |
| Process behavior | Exact argv, bounded input/output, failure/timeout/interrupt cleanup, no shell prompt interpolation | Synthetic child checks included; descendant containment pending |
| Real independent inference | Pinned `llama.cpp` executable + licensed GGUF model; nonempty response, reproducible logs | **Pending** |
| Source/evidence integrity | Distinct prompt/source hashes, versioned receipts, abstention on drift | SHA-256 prompt binding only; full evidence receipts pending |
| Platform | Windows, Linux, macOS builds and controlled subprocess runs | **Pending** |
| In-process JNI | Verified stable C ABI, Java parity tests, ownership and cancellation | **Pending** |
| Product API | Bounded HTTP/CLI, health/capability endpoints, streaming/structured outputs | CLI one-shot only; service pending |
| M3JDK21 integration | Canonical recipe provenance and independent receiver build/compatibility gates | Separate draft receiving work; **not accepted** |

A successful synthetic test is **not** evidence of live inference, model quality,
GPU acceleration or platform independence. Do not mark a gate passed until its
specific executable receipt exists.

## Migration order

1. **Diverge**: independent Java model port and browserless `llama.cpp`
   subprocess adapter; keep legacy ChromeML path unmodified.
2. **Conform**: adapt Chrome Prompt/Summarizer and ChromeML runner behind the same
   versioned contract outside the core; compare capability and failure behavior.
3. **Stand alone operationally**: native local engine service, bounded streaming
   and cancellation, model inventory/capability probe, strict output receipts,
   OpenAI-compatible surface only where actually supported.
4. **Stand alone natively**: JNI/C ABI and deterministic CPU backend; optional
   GPU/APU extensions with controlled lifecycle and measured benchmarks.
5. **Distribute**: first-class Windows/Linux/macOS packaging and platform tests;
   optional browser integrations remain installable, not required.
6. **Qualify for M3JDK21**: exact Synexia recipe outputs, per-file donor licenses
   and receiver proofs before any promotion into JDK-owned modules.

### Non-goals of the initial standalone module

It does **not** include trained model weights, implement full transformer inference
itself, provide all OpenAI-compatible endpoints, claim equivalence to Gemini Nano,
or eliminate the dependency on `llama.cpp` for the current independent provider.
The immediate accomplishment is **independence from Chrome**, not independence
from *all* engines or model assets.

## Related sources

- [Standalone Java module](../standalone/README.md)
- [Existing Chrome/native architecture inventory](ARCHITECTURE_AND_PROJECTS.md)
- [Native/HTTP boundaries](NATIVE_RUNTIME_CONTRACTS.md)
- [M3JDK21 receiving candidate](https://github.com/hsoliwal/M3jdk21/pull/2108)
- [Canonical Synexia recipe candidate](https://github.com/hsoliwal/com.synexia/pull/13973)
