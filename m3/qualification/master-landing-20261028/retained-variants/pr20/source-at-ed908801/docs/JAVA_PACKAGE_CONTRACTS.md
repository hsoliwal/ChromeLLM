# Java package contracts

[Project map](ARCHITECTURE_AND_PROJECTS.md) · [Media contracts](MEDIA_PIPELINE_CONTRACTS.md) · [Native/HTTP contracts](NATIVE_RUNTIME_CONTRACTS.md) · [Coverage](DOCUMENTATION_COVERAGE.md)

Source snapshot: `26473e370c6d7cedace4a0b21e8ad56a35ff2dfc`. All names below are packages under `io.synexia.chromellm`, not separately published artifacts. The root Maven project owns 106 main Java files in 14 namespaces. Examples are source-derived and were not executed for this documentation change.

## api

[Source](../src/main/java/io/synexia/chromellm/api) supplies immutable-at-the-array-boundary request/result records shared by all diffusion engines. `DiffusionMode` defines `UNCONDITIONED`, `IMAGE`, `DEPTH`, `CLASS_LABEL`, and `VECTOR`; the bridges pass enum ordinals to the native ABI, so reordering the enum changes that protocol.

- `DiffusionRequest(width,height,channels,steps,seed,guidanceScale,eta,condition)` requires positive dimensions/steps, channels 1/3/4, and finite nonnegative guidance/eta. Null conditioning becomes `none()`. `standard(w,h,seed)` chooses RGB, 40 steps, guidance 7.5, eta 0
- `DiffusionCondition` copies its array on construction and access; null data becomes empty. It checks nonnegative dimensions and image/depth length matching. `image`, `depth`, `classLabel`, `vector`, and `none` construct mode-specific values. It does not require positive image/depth dimensions, nor finite/ranged vector elements; callers should supply meaningful nonempty raster conditions. Constructor acceptance is not a guarantee every engine can consume a degenerate condition
- `DiffusionResult` copies pixels and metadata. `rgb8()` requires at least three channels, clamps the first three channels to 0..1 and rounds to bytes; it drops alpha and rejects one-channel conversion. Buffer size products in diffusion records use ordinary integer multiplication, unlike `RgbaFrame`'s overflow-checked calculation. Bound image sizes before allocating

Tests live in [engine/JavaDiffusionEngineTest.java](../src/test/java/io/synexia/chromellm/engine/JavaDiffusionEngineTest.java); they exercise seed behavior and normalization through the Java engine, not every record edge case.

## engine

[Source](../src/main/java/io/synexia/chromellm/engine) separates the `DiffusionEngine.generate(DiffusionRequest)` boundary from `Denoiser.predictNoise(...)`. `DiffusionEngine` is `AutoCloseable` with a default no-op close. `JavaDiffusionEngine` creates a fresh seeded `SplittableRandom`, latent array and scheduler per request, walks steps in descending order, then min/max-normalizes the final latent buffer. An injected denoiser must return the same number of values as the latent buffer; `DiffusionScheduler.step` rejects a size mismatch.

`ReferenceDenoiser` computes local 3×3 structure and mode-dependent attraction. It is a structural reference, not a trained image-generation model. The scheduler builds a linear beta schedule and applies eta-controlled noise. There is no remote I/O in these classes. No thread-safety contract is imposed on a caller-supplied denoiser, and a deterministic test with the default denoiser does not establish determinism for arbitrary implementations.

```java
var request = io.synexia.chromellm.api.DiffusionRequest.standard(16, 16, 42L);
try (var engine = new io.synexia.chromellm.engine.JavaDiffusionEngine()) {
    float[] pixels = engine.generate(request).pixels();
}
```

## batch

[Source](../src/main/java/io/synexia/chromellm/batch) owns sorted image/video discovery, per-file transform creation, output-directory mapping and failure aggregation. `BatchImageProcessor` optionally uses a fixed thread pool; `BatchVideoProcessor` is sequential. `BatchProcessResult` copies its failure list and `BatchFailure` retains input/message. Output writes and processor ownership, supplier concurrency, overwrite behavior, interruption limits and tests are covered in the [batch contract](MEDIA_PIPELINE_CONTRACTS.md#batch-package).

## chrome

[ChromeAiConditioningClient](../src/main/java/io/synexia/chromellm/chrome/ChromeAiConditioningClient.java) appends `/v1/chat/completions` to a base URI and synchronously sends system/user messages through Java `HttpClient`. Connect timeout is ten seconds; request timeout is two minutes. The request selects `chrome-gemini-nano` with temperature 0.2 and sends the user's prompt to that endpoint.

`plan(String)` reads the first choice's message, strips an optional outer Markdown fence, parses JSON, clamps supplied vector values to 0..1, and prepends the low 16 bits of `class_id` normalized by 65535. Although the prompt asks for 16 vector values, code accepts any nonempty vector. Empty vectors, non-2xx status and JSON/I/O failures become `IllegalStateException`; interruption restores the interrupt flag before wrapping it. There is no built-in authentication header, retry, or endpoint allowlist. Choose the endpoint deliberately. No dedicated planner test is present.

## nativebridge

[Source](../src/main/java/io/synexia/chromellm/nativebridge) implements the same diffusion interface through `JnaDiffusionEngine` and `JniDiffusionEngine`. JNA obtains one C handle for each `generate` and destroys it in `finally`; JNI performs create/generate/destroy in the native adapter. `NativeDiffusionLibrary` is the public JNA mapping; `JniDiffusion` is package-private native plumbing. Library names are configured through `chromellm.diffusion.library` and `chromellm.diffusion.jni.library`. Load failures may surface as linkage errors before generation. See [native ownership and errors](NATIVE_RUNTIME_CONTRACTS.md). No Java/native pixel-parity test is declared in this snapshot.

## cli

[Main](../src/main/java/io/synexia/chromellm/cli/Main.java) selects `java`, `jna`, or `jni`, optionally calls the Chrome planner for `--prompt`, generates RGB output, and closes its engine. Defaults: 256×256, 40 steps, seed 1, guidance 7.5, eta 0, `diffusion.ppm`, local chat base `http://127.0.0.1:11435`. Missing values/unknown flags fail with `IllegalArgumentException`; numeric parsing errors propagate. Package-private `PpmWriter` writes a binary P6 header and RGB bytes with `Files.write`, creating/replacing the selected file without creating parent directories. The command is not a model downloader or packaged JAR launcher.

## gpu

[Source](../src/main/java/io/synexia/chromellm/gpu) owns RGBA8 values, pixel operations, mask helpers and backend choice. `RgbaFrame` requires positive dimensions and exactly `width*height*4` bytes using `Math.multiplyExact`; it copies input/output arrays. `AlphaMask` similarly owns one byte per pixel; `fromFrameLuma` derives mask values from RGB luma, not source alpha. `PixelParameters` rejects nonfinite values but interpretation is operation-specific.

`ImageProcessor` exposes `process`, `blend`, `generate`, backend name, acceleration status and close. `CpuImageProcessor` returns new frames. Copy/invert/grayscale/brightness-contrast/gamma/threshold/channel-scale/Sobel/box-blur are implemented; blur clamps radius to 1..8 and blurs alpha too. Gamma clamps to at least 0.01; contrast 0 means 1; threshold values ≤1 are scaled to byte intensity; channel alpha scale 0 means 1. These zero-default rules matter when constructing parameters directly.

`blend` requires equal geometry, clamps opacity, and weights RGB by overlay alpha×opacity; output alpha uses the source-over expression. It is not a general color-managed, premultiplied-alpha compositor. Procedural generators are solid, gradient, seeded noise, plasma and checkerboard. Java and OpenCL use different noise hashes and some rounding/range rules; backend selection can change pixels.

`ObjectComposer.place` clips an overlay to the background then blends. `MaskOps.removeApprox` repeatedly blurs (1..16 passes; radius 1..8) and blends through mask alpha; it is an approximate fill, not semantic object removal. `AlphaMasks` offers inversion, threshold 0..255, and separable feather radius 1..64. `ImageIoFrames.read` rejects unsupported ImageIO formats. `write` uses JPEG only for `.jpg`/`.jpeg`; every other extension is encoded as PNG. JPEG output drops transparency. This matters when batch processing BMP/GIF filenames.

`GpuProcessorFactory.AUTO` tries JNA OpenCL, then JNI OpenCL, catching initialization `Throwable`, then chooses Java CPU. Explicit JNA/JNI selection propagates initialization failure. Inspect `backendName()` and `hardwareAccelerated()` rather than assuming AUTO means GPU. See [CpuImageProcessorTest](../src/test/java/io/synexia/chromellm/gpu/CpuImageProcessorTest.java) and [CompositingTransformsTest](../src/test/java/io/synexia/chromellm/video/CompositingTransformsTest.java).

## gpu.nativebridge

[Source](../src/main/java/io/synexia/chromellm/gpu/nativebridge) owns a long-lived OpenCL context per JNA/JNI processor. Constructors accept `allowCpuFallback`; the main factory passes false and performs its own Java fallback. Both wrappers reject operations after close and make repeated close harmless by clearing the handle. They allocate/copy Java byte arrays for calls; this is not a zero-copy buffer API. JNA errors include operation/return code and `gpu_last_error`; JNI translates native failure into `IllegalStateException`. The context's mutable kernels are not synchronized: serialize use and do not race close with operations. Configuration and C ABI are [documented separately](NATIVE_RUNTIME_CONTRACTS.md).

## gpu.cli

[GpuMain](../src/main/java/io/synexia/chromellm/gpu/cli/GpuMain.java) dispatches image/generate/place/remove/video/batch/info to an `ImageProcessor`; inpaint/clone/grabcut/interpolate commands use `VisionProcessor`; analyze-video needs only a decoder. It closes the processor it opens. `--backend`, `--vision-backend`, and `--decoder` default to AUTO. `--preset` selects a compiled preset instead of a direct pipeline; selected encoding flags override preset encoding values. Numeric and enum failures propagate rather than being silently corrected.

The parser accepts arbitrary `--key` names and assigns string `true` to a flag without a value. Unknown keys can therefore be ignored; do not treat absence of a parse error as validation of a misspelled option. Batch-image forces parallelism to one for hardware-accelerated processors or any preset; other image batches construct fresh transforms and use requested parallelism. Batch-video is sequential. Per-file failures are printed, but these methods do not explicitly set a failing process exit code when a batch has failures. See the [media lifecycle](MEDIA_PIPELINE_CONTRACTS.md) before relying on batch output as a success signal.

## preset

[Source](../src/main/java/io/synexia/chromellm/preset) contains seven records plus `MediaPresetLoader`. `load(Path,ImageProcessor)` reads JSON with Jackson and accepts only version 1. It composes, in order: parsed pipeline, optional LUT, overlays in list order, and automation in list order. Asset paths are resolved relative to the preset's directory, but absolute paths and `..` traversal are accepted; preset loading is not a filesystem sandbox. Loading performs immediate LUT/image file I/O.

Missing overlay coordinates default to zero, opacity to one, frame interval to `[0,Long.MAX_VALUE)`. Missing automation curves become constant zero; names normalize hyphens to underscores and uppercase enum names. Keyframes require frame and value. Missing encoding uses `VideoEncodingOptions.defaults`; missing progress interval is 30. `CompiledMediaPreset` requires nonnull transform/encoding and positive progress interval. Input records are parsing carriers, not a promise of independent deep immutability. The returned transform may contain temporal state; start/reset it for each stream and do not share it concurrently. See [MediaPresetLoaderTest](../src/test/java/io/synexia/chromellm/preset/MediaPresetLoaderTest.java).

## video and video.nativebridge

[video](../src/main/java/io/synexia/chromellm/video) is the 54-file stream/transform package. [video.nativebridge](../src/main/java/io/synexia/chromellm/video/nativebridge) is its two-file direct-decoder adapter. [MEDIA_PIPELINE_CONTRACTS.md](MEDIA_PIPELINE_CONTRACTS.md) documents all cohesive type groups, stream ownership, ordering, shape, failures, encoding and tests. These namespaces are part of the same JAR as pixel operations; no separate video artifact is declared.

## vision

[Source](../src/main/java/io/synexia/chromellm/vision) exposes `VisionProcessor.inpaint`, `seamlessClone`, `grabCut`, `interpolate`, backend name and default no-op close. `VisionProcessorFactory.AUTO` tries OpenCV then catches initialization failure and uses Java. Explicit OPENCV fails rather than falling back.

The Java implementation deliberately approximates these operations: inpaint uses blurred masked replacement; seamlessClone is centered alpha placement; grabCut returns the clipped rectangle mask and does not perform segmentation or use `iterations`; interpolation is a clamped blend. This fallback preserves an entry-point shape, not algorithmic or quality parity with OpenCV. [JavaVisionProcessorTest](../src/test/java/io/synexia/chromellm/vision/JavaVisionProcessorTest.java) asserts those fallback semantics.

## vision.nativebridge

[Source](../src/main/java/io/synexia/chromellm/vision/nativebridge) wraps the optional OpenCV JNI target. `OpenCvVisionProcessor` caches its native version string, validates matching frame/mask geometry, copies frame arrays to native calls, and returns copied frame/mask values. `OpenCvJni` loads `chromellm.opencv.jni.library` (default `chromellm_opencv_jni`). Native calls use local OpenCV objects rather than a Java-owned persistent context; close inherits the no-op. Native errors become `IllegalStateException`. [Native operation differences](NATIVE_RUNTIME_CONTRACTS.md) and the opt-in [integration test](../src/test/java/io/synexia/chromellm/vision/OpenCvVisionProcessorIntegrationTest.java) describe the actual boundary.

## Test source packages

The six test namespaces reuse production qualified package names under a different source root. They are test-only and included in coverage accounting. `batch` checks file traversal/output separation; `engine` checks Java seed/normalization behavior; `gpu` checks pixel/mask/array behavior; `preset` checks compilation/version rejection; `video` checks transforms, LUT parsing, rates, encoding command construction and opt-in decoder integration; `vision` checks fallback behavior and opt-in OpenCV calls. Full test navigation and unexecuted checks are in the [coverage ledger](DOCUMENTATION_COVERAGE.md).
