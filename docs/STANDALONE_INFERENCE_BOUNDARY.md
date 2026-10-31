<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors -->

> **Historical snapshot, not a runnable Chrome integration:** ChromeLLM draft PR #19 removes the old ChromeML/Dawn, Node and PowerShell execution files. Current text inference uses a separate llama.cpp JNI backend (see [START_HERE](START_HERE.md)). The legacy architectures, links and verification counts below are retained solely for provenance; do not follow them as build instructions. Real GGUF inference remains to be qualified.
<!-- SPDX-License-Identifier: Apache-2.0 -->
# ChromeLLM: standalone inference architecture

**Status:** draft architecture contract; not a completed standalone runtime.
**Product owner:** `hsoliwal/ChromeLLM`. **Canonical recipe/evidence owner:**
private `hsoliwal/com.synexia`. **Java SDK owner:** `hsoliwal/M3jdk21`.

## Decision and the irreversible split

ChromeLLM must diverge from Chrome and ultimately run as an independent
local inference product. Chrome's built-in `LanguageModel` and `Summarizer`
APIs remain optional providers, never permanent owners of the core contract,
source handling, Java/JNI interface, model lifecycle, receipts or deployment.

There are TWO separate milestones. **Browser/UI independence** means a native
process can infer without a Chrome tab, extension or browser window; it may
still need ChromeML/Dawn libraries and separately supplied licensed model
assets. **Runtime/model independence** means a portable, separately licensed
engine and model/tokenizer/config run on a machine with no Chrome installation,
ChromeML DLLs, browser profiles or Chrome-managed model assets. The second
milestone is the actual standalone product acceptance. Neither follows from
source compilation or a fake-model smoke test.

## Existing source owners to extend

- `runtime/chromeml_api_server.cjs`: Node loopback HTTP adapter; converts
  supported chat requests, queues jobs and invokes the Windows PowerShell
  runner. Its OpenAI-compatible routes are a subset, not the whole API.
- `runtime/ChromeMLBareRunner.cpp`: Windows native process using compatible
  ChromeML/Dawn runtime assets and an externally supplied model. A separable
  process is not, by itself, an independent model runtime.
- Java/JNA/JNI diffusion and FFmpeg/OpenCV media code are independent
  workloads, not evidence of an independently trained text or image model.
- Synexia [PR #14186](https://github.com/hsoliwal/com.synexia/pull/14186)
  already has the sealed `synexia.atom-pattern-lld.v1` generation contract
  and `engine.generateCodeCandidate` seam. Reuse them, not another parser,
  atomizer/patternizer, source writer or recipe scheduler.
- M3JDK21 [PR #2108](https://github.com/hsoliwal/M3jdk21/pull/2108)
  contains a draft `com.m3.llm` module with browser/native summary and
  extraction APIs. Its `CPU/GPU/APU` enum names compute devices, not providers.

## Dependency direction

```text
Java / JNI / CLI / SWT / browser PWA / local HTTP client
                         |
               versioned inference request
                         |
                 provider-neutral SPI
        +----------------+----------------+
        |                |                |
 Chrome Prompt API   ChromeML native   Portable local engine
 optional browser    ChromeML assets   independent licensed model
        +----------------+----------------+
                         |
                 CPU / GPU / APU
                         |
              bounded candidate + receipt
                         |
         Synexia canonical recipe qualification
                         |
    accepted result-only ChromeLLM / M3JDK21 product
```

ChromeLLM's installed runtime MUST NOT require Synexia or Chrome's browser
globals to function. Synexia exclusively owns Maven/OpenRewrite recipes,
source-atom/pattern catalogues, source mutation, test fixtures, execution
and qualification. Product repositories may receive only executed,
qualified result-only postimages, passive recipe/source receipts and
product-owned tests. No product repository hosts a recipe engine.

## Proposed stable protocol: `chromellm.inference.v1`

Define a versioned provider-neutral contract that works over Java/JNI,
loopback HTTP, stdio or an in-process native boundary. Its draft shapes:

- **ProviderId**: `CHROME_BUILT_IN_PROMPT_API`, `CHROMEML_NATIVE`,
  `PORTABLE_LOCAL`. Selection is explicit. Never silently select another
  provider, retry generation, call a cloud model or substitute weights.
- **ComputeBackend**: CPU, GPU or APU, recorded independently of provider
  identity. Report actual device execution rather than requested execution.
- **Capabilities**: available text/media modalities, language versions,
  structured JSON/schema support, max context/output sizes, offline mode,
  user activation/model-download requirements, hardware and cancellation
  support. Refuse unsupported capabilities before generation.
- **ModelManifest**: exact model/runtime ID and version; tokenizer/config/
  checkpoint digest(s) where present; origin, license, redistribution rights
  and compatible ABI. A pathname/hash does not establish a license.
- **InferenceRequest**: protocol and request IDs, selected provider/model/
  device, immutable manifest digest, prompt/schema and SHA-256, output bound,
  request deadline/cancellation, and explicit permissions. A source-code
  request additionally binds one source path, source SHA-256 or checked
  `ABSENT`, frozen interface, canonical LLD SHA-256, recipe ID, and
  atomization/patternization receipt identities.
- **InferenceResult**: bounded candidate text/JSON, uncertainties,
  completion/error classification, exact source/request/artifact digests,
  provider/runtime/model identity, generation-call count, and optional
  actually measured token/time/resource counters. No invented metrics or
  unsigned model attestation.

Opening a model session or explicitly downloading permissible assets is NOT
a code-generation invocation. For a reviewed atomized/patternized Java LLD,
one selected file permits **exactly one model generation call** for one
implementation proposal. The Java interface is deterministic, frozen and
not model-editable. Unsupported schema, invalid JSON, missing/changed hashes,
resource exhaustion, cancelled requests and partial completions return HOLD
without fallback or retry. A new explicit run receives a new request ID.

All code generation is `HOLD_UNVERIFIED` with
`sourceWriteAuthorized=false` and `promotionAuthorized=false` until
actual canonical Synexia recipe execution plus Java 21 compiler/JUnit/ABI/
JNI/behavior and receiving-product gates. A receipt hash proves identity
consistency, not model execution or semantic equivalence.

## Ordered admission gates

| Stage | Work | Actual evidence before promotion |
| --- | --- | --- |
| 0 - SPI | Provider-neutral request, capability, response and receipt semantics | Contract tests, one-call/no-fallback, schema/drift/cancel refusals |
| 1 - process split | Native ChromeML runner available without browser UI | Actual Windows native model inference, asset/ABI pins, child lifecycle |
| 2 - independent model | Legally usable, independent local engine + weights/tokenizer | **No-Chrome-installed** real inference test, legal provenance |
| 3 - portability | CPU baseline then optional GPU/APU across OS targets | Linux/Windows/macOS integration, device proof, memory/performance |
| 4 - product receiving | M3JDK21 and ChromeLLM qualified runtime integration | Executed Synexia Maven/OpenRewrite recipe, fixed point/drift refusal, target-native checks |

Do not turn an HTTP response, mock result, stock-JDK module image or source
rebuild into claims of installed-product readiness. Retain open gates as
PENDING and compare performance only when measured.

## Security and model rights

The existing Node server permits wildcard CORS, does not use Authorization
headers as authentication, and accepts some caller-controlled local media
paths. Keep it loopback-only until real authentication, origin/path limits,
resource policy and user permission have been implemented and tested. Model
output has no authority to run shell commands or write source files. Bound
subprocess input/output, cancellation and cleanup.

Do not copy, redistribute, translate or commit Chrome/Gemini proprietary
`weights.bin`, Chrome-owned DLLs, browser profiles, cookies or other restricted
assets. Audit the already tracked native executable and DLL provenance before
shipping binaries. Open native runtimes (e.g. potential llama.cpp or ONNX
Runtime backends) and open-weight models are independent candidates only
after exact tokenizer, checkpoint, ABI, model and license review.

## Implementation order and source authority

1. Refine/execute the existing Maven/OpenRewrite hash-pinned recipes in
   Synexia against exact preimages, including a fixed-point and drift refusal.
   Product code cannot be hand-patched to imitate a recipe output.
2. Make the existing `generateCodeCandidate` seam provider-neutral without
   changing the frozen LLD/Java-interface contract or one-call rule.
3. Qualify ChromeML as a process provider using current explicit external
   assets, and test cancellation, bounded output, source seals and cleanup.
4. Admit a second independent licensed native engine/model and prove it
   runs on a host with no Chrome/ChromeML installation.
5. Export only qualified product postimages to ChromeLLM and M3JDK21,
   preserving provenance and independently executing receiving tests.

**Not established by this design record:** a working standalone model, legal
right to redistribute Chrome weights/DLLs, live inference, full OS parity,
M3JDK21 native integration, or performance gains.
