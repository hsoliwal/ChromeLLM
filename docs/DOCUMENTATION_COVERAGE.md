# Documentation coverage and verification ledger

[Project index](ARCHITECTURE_AND_PROJECTS.md) · [Package contracts](JAVA_PACKAGE_CONTRACTS.md) · [Media contracts](MEDIA_PIPELINE_CONTRACTS.md) · [Native/runtime contracts](NATIVE_RUNTIME_CONTRACTS.md)

## Scope and evidence

Source snapshot: `26473e370c6d7cedace4a0b21e8ad56a35ff2dfc`, read through the GitHub connector on 14 October 2026. The recursive tree was returned with `truncated=false`: 177 tracked files, including 106 main Java files and 17 test Java files. The revision includes earlier documentation edits; source/build behavior was not changed by this batch. Paths and declarations, rather than folder names alone, establish the boundaries below.

The source review read complete Java implementation/test bodies and all eight native source/header files. It also read the HTTP adapter, PowerShell entry points, build manifests, CI and both analysis-overlay scripts. The large Windows runner was inventoried and its call boundary inspected; exhaustive callback/ABI semantics remain open. Reference/generated headers and binary interiors were not fully semantically reviewed.

These additions are Markdown-only external guides. Existing Java declarations, Javadocs, APIs, build scripts, dependencies, workflows, licenses and notices are unchanged. In particular, package-level native documentation and exhaustive inline API semantics remain unfinished. No claim of complete M3 verification, full repository coverage, hardware correctness, or production readiness is made.

## Separate level accounting

“Partial” means useful source-backed documentation exists but the strict completion gate is not met. “Reviewed-complete” requires both full applicable semantics and relevant verification receipts; no entity is promoted merely because it appears in a table. All discovered entities in these four defined levels are included; blocked verification remains in the denominator.

| Level | Discovered | Included | Excluded | Unresolved scope | Blocked entities | Reviewed-complete | Partial | Missing | Documented coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Repository | 1 | 1 | 0 | 0 | 0 | 0 | 1 | 0 | 0/1 |
| Independently configured projects | 6 | 6 | 0 | 0 | 0 | 0 | 6 | 0 | 0/6 |
| Build/runtime/tooling modules | 11 | 11 | 0 | 0 | 0 | 0 | 11 | 0 | 0/11 |
| Main Java namespaces | 14 | 14 | 0 | 0 | 0 | 0 | 14 | 0 | 0/14 |
| Test Java namespaces (source-set identities) | 6 | 6 | 0 | 0 | 0 | 0 | 6 | 0 | 0/6 |
| Maven package/artifact coordinates | 2 | 2 | 0 | 0 | 0 | 0 | 2 | 0 | 0/2 |

Package total is 22 included identities. Namespace and artifact counts have distinct meanings and are shown separately. The four-level totals are 1 repository + 6 projects + 11 modules + 22 packages = 40 records. No package-manager distribution is invented for the Node file; no JPMS module is invented. Accounting is complete for these declared boundary categories, while substantive completion remains partial. Verification stages blocked/not run are described below rather than hidden as scope exclusions.

Projects/modules and their containment/dependencies are enumerated in [ARCHITECTURE_AND_PROJECTS.md](ARCHITECTURE_AND_PROJECTS.md). The two Maven artifact coordinates are `io.synexia:chromellm-diffusion:1.0.0-SNAPSHOT` and `com.synexia.intake:repository-hierarchy-intake:1`; the second is a POM-only analysis dependency/exec configuration, not a second application JAR.

## Namespace ledger

Each row is included and partial. Main contracts link to the corresponding package section; test rows link to their source evidence and the test-purpose map below.

| Source set | Qualified namespace | Files | Contract / source evidence |
| --- | --- | ---: | --- |
| main | `io.synexia.chromellm.api` | 4 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#api) |
| main | `io.synexia.chromellm.batch` | 4 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#batch) |
| main | `io.synexia.chromellm.chrome` | 1 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#chrome) |
| main | `io.synexia.chromellm.cli` | 2 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#cli) |
| main | `io.synexia.chromellm.engine` | 5 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#engine) |
| main | `io.synexia.chromellm.gpu` | 12 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#gpu) |
| main | `io.synexia.chromellm.gpu.cli` | 1 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#gpucli) |
| main | `io.synexia.chromellm.gpu.nativebridge` | 4 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#gpunativebridge) |
| main | `io.synexia.chromellm.nativebridge` | 4 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#nativebridge) |
| main | `io.synexia.chromellm.preset` | 8 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#preset) |
| main | `io.synexia.chromellm.video` | 54 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#video-and-videonativebridge) |
| main | `io.synexia.chromellm.video.nativebridge` | 2 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#video-and-videonativebridge) |
| main | `io.synexia.chromellm.vision` | 3 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#vision) |
| main | `io.synexia.chromellm.vision.nativebridge` | 2 | [contracts/evidence](JAVA_PACKAGE_CONTRACTS.md#visionnativebridge) |
| test | `io.synexia.chromellm.batch` | 2 | [contracts/evidence](../src/test/java/io/synexia/chromellm/batch) |
| test | `io.synexia.chromellm.engine` | 1 | [contracts/evidence](../src/test/java/io/synexia/chromellm/engine) |
| test | `io.synexia.chromellm.gpu` | 1 | [contracts/evidence](../src/test/java/io/synexia/chromellm/gpu) |
| test | `io.synexia.chromellm.preset` | 1 | [contracts/evidence](../src/test/java/io/synexia/chromellm/preset) |
| test | `io.synexia.chromellm.video` | 10 | [contracts/evidence](../src/test/java/io/synexia/chromellm/video) |
| test | `io.synexia.chromellm.vision` | 2 | [contracts/evidence](../src/test/java/io/synexia/chromellm/vision) |

## Test-purpose map

All 17 test classes were inspected as source; this is not an execution receipt.

| Test package | Declared test classes and scope | Gaps |
| --- | --- | --- |
| batch | BatchImageProcessorTest (recursive PNG output, sources preserved, same directory); BatchVideoProcessorTest (same directory) | Parallel failure handling and actual video batch execution not established |
| engine | JavaDiffusionEngineTest (same/different seeds, normalized output) | Native parity, custom denoiser contracts and all condition edges |
| gpu | CpuImageProcessorTest (invert/alpha, blending, placement, seeded noise, defensive copies) | OpenCL execution/parity, device errors, concurrency |
| preset | MediaPresetLoaderTest (pipeline/automation/encoding, unsupported version) | Every malformed input, asset path and concurrent reuse case |
| video | CompositingTransformsTest, CubeLutParserTest, FfmpegProbeTest, GeometryTransformTest, KeyframeCurveTest, TemporalPipelineTest, TransformChainTest, TransformSpecParserTest, VideoEncodingOptionsTest | Process timeout/cancellation/exit handling, every transform bound and codec/container combination |
| video integration | JniFfmpegFrameSourceIntegrationTest creates a fixture with FFmpeg, decodes and seeks | Enabled only when `chromellm.native.integration=true`; not Windows ChromeML inference |
| vision | JavaVisionProcessorTest and OpenCvVisionProcessorIntegrationTest | Native test is property-gated; selected inpaint/interpolation smoke behavior is not all OpenCV APIs or quality validation |

## Special areas retained in scope

| Area | Classification and disposition | Remaining work |
| --- | --- | --- |
| `.m3/atom-pattern` | Included tooling project/module/POM artifact; source-backed configuration guide | External recipe implementation/resolution and actual run remain unverified |
| `.m3/atomize-patternize` | Included tooling project/module; no bound inventory in pinned tree | Bootstrap/run not executed; complete runtime/tool dependency validation pending |
| `reference/` | Three upstream reference files accounted as reference material, not new first-party package identities | Detailed upstream ABI attribution and compatibility review remains outside completed semantics |
| `runtime/tag_148_headers/` and two generated `.inc` files | Four generated/ABI support files accounted; source of truth retained | Regeneration recipe/version compatibility needs verification |
| `runtime/ChromeMLBareRunner.exe`, `optimization_guide_internal.dll`, `webgpu_dawn.dll` | Three opaque binary artifacts accounted; no execution/decompilation claim | Notice/tree discrepancy and redistribution provenance unresolved |
| `data/` | Three image/audio fixtures accounted | Their presence is not an executed media test |
| `model/` | README and placeholder accounted | Weights absent; no download/inference performed |
| Root docs, `docs/`, workflow and launcher/config files | Supporting documentation/configuration/assets accounted | No separate package invented; unchanged upstream docs may retain drift |

## Verification receipt summary

- PASS: complete nontruncated pinned tree retrieval; source declaration/manifests inventory; source-linked contract authoring
- PASS after publication review: changed-path allowlist and relative source/document navigation checked against the pinned tree plus added Markdown files; this does not execute examples or validate live external services
- NOT_RUN: repository documentation linter (none established for this batch), Maven compilation/test, CMake configure/build, opt-in integration, OpenCL device execution, Windows runner build/model inference, `.m3` recipe runs
- NOT_RUN reason: the authorized batch is connector-based documentation editing without repository code/build/test execution. Existing CI is observed separately at the actual PR head and must not be conflated with local checks or full behavioral coverage
- No dependency remediation, generated-source refresh, license change, model download, deployment, merge, rebase or force-push is part of this documentation batch

## Open completion gates

1. Review important public APIs inline and supply repository-native package/Javadoc semantics where needed; Markdown guides do not satisfy that remaining requirement
2. Finish Windows runner callback/model/ABI review and establish binary provenance without changing notices by inference
3. Execute the documented verification sequence in an authorized compatible environment and record exact revision, command, result, backend and skipped gates
4. Expand edge-case tests for fallback parity, native concurrency/resource cleanup, media cancellation/partial outputs, and malformed preset/media inputs if separately authorized
5. Reconcile any future source change with the pinned contracts; a passing CI run cannot automatically update semantic coverage
