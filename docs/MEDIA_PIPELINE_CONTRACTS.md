# Media stream, transform, and batch contracts

[Project map](ARCHITECTURE_AND_PROJECTS.md) · [Java packages](JAVA_PACKAGE_CONTRACTS.md) · [Native contracts](NATIVE_RUNTIME_CONTRACTS.md) · [Coverage](DOCUMENTATION_COVERAGE.md)

Observed at `26473e370c6d7cedace4a0b21e8ad56a35ff2dfc`; examples and tests were not run in this documentation pass. All type names in this page belong to `io.synexia.chromellm.video` unless stated otherwise.

## Stream processing lifecycle

[`FfmpegVideoProcessor.process`](../src/main/java/io/synexia/chromellm/video/FfmpegVideoProcessor.java) owns its decoder and encoder, but borrows the caller's `ImageProcessor` and `FrameTransform`.

1. Open a `VideoFrameSource`, get `VideoStreamInfo`, and ask a `FrameTransformShape` for output geometry when implemented
2. Call `FrameTransformLifecycle.onStreamStart(inputInfo)` when implemented
3. Start the FFmpeg encoder using output geometry; before every read, check `ProcessingControl`
4. Read one frame, apply the transform at zero-based frame index, reject null or unexpected output geometry, then write it
5. Report progress at the requested positive frame interval; close the encoder and call `onStreamEnd` in the processing `finally`; report final progress on the successful path; close the source

The `onStreamStart` call occurs before the corresponding processing `try/finally`, so a start-hook exception is not followed by this method's `onStreamEnd`. `onStreamEnd` is not an error-aggregating cleanup protocol; exceptions can interrupt later cleanup callbacks. `ProcessingControl.checkCancelled` throws `ProcessingCancelledException`. Cancellation is checked between frames; it does not interrupt a blocked native decode, process pipe read, transform, or encoder close. The CLI currently supplies `NEVER_CANCELLED`.

`ProcessingProgress` reports count, wall-clock elapsed time and processing FPS with nonnegative/finite checks. `VideoProcessResult` records output, frame count, output stream, elapsed time and backend string, and derives processing FPS/realtime factor. These values are per-run observations; they are not repository performance claims. Listener exceptions propagate. There is no rollback or automatic deletion of partial output after failure/cancellation.

## Decode backends and resource boundaries

[`VideoFrameSource`](../src/main/java/io/synexia/chromellm/video/VideoFrameSource.java) returns RGBA frames or null at EOF and must be closed. `VideoStreamInfo` requires positive dimensions and finite positive FPS, and computes RGBA byte size with overflow checking. `DecoderBackend` selects PROCESS, JNI or AUTO; [`VideoFrameSources`](../src/main/java/io/synexia/chromellm/video/VideoFrameSources.java) tries JNI first in AUTO and falls back on any initialization `Throwable`. A later read failure does not switch backends midstream.

[`ProcessFfmpegFrameSource`](../src/main/java/io/synexia/chromellm/video/ProcessFfmpegFrameSource.java) probes first, then starts `ffmpeg` selecting `0:v:0`, rawvideo RGBA to stdout and inherited stderr. Reads accumulate exactly one frame; a partial frame throws `EOFException`. Repeated reads after EOF return null; reads after close fail. Close closes stdout then waits for the process with no fixed timeout; interrupted waiting restores the interrupt flag, forcibly destroys the process and throws `IOException`. At this snapshot, a nonzero decoder exit is reported only when EOF has not already been observed. Therefore EOF alone is not proof of a clean decoder exit.

[`FfmpegProbe`](../src/main/java/io/synexia/chromellm/video/FfmpegProbe.java) selects `v:0` through `ffprobe`, expects width/height/r_frame_rate as three lines, and accepts rational or decimal rates. Nonzero exit/short output is an `IOException`; bad numeric values and zero denominator can throw unchecked parsing/argument errors. Default executable overrides are `chromellm.ffprobe` and `chromellm.ffmpeg`.

[`JniFfmpegFrameSource`](../src/main/java/io/synexia/chromellm/video/nativebridge/JniFfmpegFrameSource.java) opens an absolute path, owns a native handle, allocates an RGBA buffer per frame and invalidates the handle on close. Return 0 means EOF; a negative return becomes `IOException`, but native JNI code also throws `IllegalStateException` for many failures before Java can inspect that return. `seekMillis` asks FFmpeg for a backward seek and resets decoder state; it does not promise exact-frame positioning. The native selector uses FFmpeg's best video stream, which can differ from process backend's explicit first stream. Native fallback FPS is 30 if rate discovery fails. Do not assume backend-identical selection/timing.

## Encoding is always an external process

[`FfmpegRgbaEncoder`](../src/main/java/io/synexia/chromellm/video/FfmpegRgbaEncoder.java) is package-private. It pipes RGBA bytes to a separate FFmpeg process even when decoding uses JNI. It also opens the original input to map optional audio. `VideoCodec` maps H264/H265/AV1/VP9 to libx264/libx265/libaom-av1/libvpx-vp9; actual codec availability depends on the installed FFmpeg build.

`VideoEncodingOptions.defaults()` uses H264, veryfast, CRF 18, yuv420p, COPY audio, 192 kbps, automatic thread count (0), and no extra arguments. The record checks CRF 0..63, audio bitrate 32..1536 kbps, threads 0..256 and nonblank extra arguments. These are generic bounds, not proof that every codec accepts every combination. H264/H265 receive preset and CRF; AV1/VP9 receive CRF and zero target video bitrate.

The command includes `-y`, so output can be overwritten. AudioMode COPY/AAC map `1:a?`; NONE adds `-an`. `-shortest` applies. yuv420p requires even width and height; yuv422p requires even width, checked before process startup. `extraArguments` are appended as individual process arguments before the output path; no shell is used, but arbitrary FFmpeg options are not sandboxed. Only use trusted presets/options and a deliberate output destination.

Close is idempotent, closes stdin, waits for encoding completion, and reports nonzero exit as `IOException`; a close-stream failure can carry suppressed exit/interruption failure. There is no encoder wait timeout. Use compatible container/audio/video selections rather than assuming the filename determines safe settings.

## Transform protocol and full type-family map

`FrameTransform.apply(RgbaFrame,long)` is the processing interface. `FrameTransformShape.outputStreamInfo` declares geometry; `FrameTransformLifecycle` adds optional start/reset/end hooks. These are orthogonal interfaces, not a guarantee every transform is stateless or thread-safe.

| Type family | Implemented contract and boundary | Evidence |
| --- | --- | --- |
| `TransformChain` | Copies the transform list; applies left-to-right and rejects null results; propagates shape left-to-right; starts hooks with the geometry at that stage; resets in forward order; ends in reverse order | [source](../src/main/java/io/synexia/chromellm/video/TransformChain.java), [order test](../src/test/java/io/synexia/chromellm/video/TransformChainTest.java) |
| `GpuFrameTransform`, `KeyframedPixelTransform` | Borrow a processor and call `process`; keyframed version evaluates four curves at the current index | [source](../src/main/java/io/synexia/chromellm/video/KeyframedPixelTransform.java) |
| `Keyframe`, `KeyframeCurve`, `Easing` | Nonnegative frame indices and finite values; sort input; reject duplicate indices; clamp outside endpoints; interpolate using the left keyframe's easing; an exact right keyframe returns that right value, including HOLD intervals | [curve](../src/main/java/io/synexia/chromellm/video/KeyframeCurve.java), [tests](../src/test/java/io/synexia/chromellm/video/KeyframeCurveTest.java) |
| `GeometryOperation`, `GeometryTransform`, `ResizeTransform`, `CropTransform` | Flips/rotations remap pixels; quarter-turns swap dimensions; resize uses bilinear sampling; crop clips a `FrameRegion` and fails if there is no overlap; each shape-changing transform declares its output geometry | [geometry](../src/main/java/io/synexia/chromellm/video/GeometryTransform.java), [tests](../src/test/java/io/synexia/chromellm/video/GeometryTransformTest.java) |
| `FrameRegion`, `FrameRegions`, `RegionTransform` | Regions need positive size; crop/paste copy pixels; region transform operates on a cropped patch then pastes it back, rejecting any patch geometry change | [region](../src/main/java/io/synexia/chromellm/video/RegionTransform.java) |
| `FrameRangeTransform` | Applies only on `[startInclusive,endExclusive)`, validates nonnegative/nonempty range, delegates lifecycle, rejects declared dimension changes at stream start | [range](../src/main/java/io/synexia/chromellm/video/FrameRangeTransform.java) |
| `FrameMaskProvider`, `MaskedTransform` | Obtain a same-sized mask per frame; linearly combine all four channels by mask intensity; reject null/mismatched mask and changed transform geometry; delegate lifecycle | [mask](../src/main/java/io/synexia/chromellm/video/MaskedTransform.java), [compositing tests](../src/test/java/io/synexia/chromellm/video/CompositingTransformsTest.java) |
| `OverlayImageTransform` | Borrows processor, stores overlay/placement, validates opacity 0..1, and delegates clipped alpha placement to `ObjectComposer` | [overlay](../src/main/java/io/synexia/chromellm/video/OverlayImageTransform.java) |
| `ColorMatrixTransform` | Copies exactly 20 finite coefficients, applies a 4×5 RGBA matrix; bias terms are multiplied by 255 before clamping/rounding | [matrix](../src/main/java/io/synexia/chromellm/video/ColorMatrixTransform.java) |
| `CubeLut`, `CubeLutParser`, `Lut3dTransform` | Red-fastest 3D table, size 2..256, trilinear sampling with clamped coordinates; parser reads whole text file, supports 3D domain/range directives, rejects 1D/shaper sections and wrong table length; transform preserves alpha and mixes RGB with intensity 0..1 | [parser](../src/main/java/io/synexia/chromellm/video/CubeLutParser.java), [LUT tests](../src/test/java/io/synexia/chromellm/video/CubeLutParserTest.java) |
| `UnsharpMaskTransform` | Uses borrowed processor's box blur, then sharpens RGB when delta reaches threshold; preserves source alpha; radius 1..8, amount 0..5, threshold 0..255 | [unsharp](../src/main/java/io/synexia/chromellm/video/UnsharpMaskTransform.java) |
| `FrameStatistics`, `SceneCutDetector`, `SceneCut` | Normalized luma statistics and a 64-bin histogram; cut score is pixel difference × (0.75 + 0.25×histogram distance); standard threshold 0.35 and sampling stride 4 | [detector](../src/main/java/io/synexia/chromellm/video/SceneCutDetector.java) |
| `TemporalDenoiseTransform` | Holds previous source/output, blends within a scene, resets on cut and stream boundaries; current-frame weight must be in (0,1] | [temporal source](../src/main/java/io/synexia/chromellm/video/TemporalDenoiseTransform.java) |
| `TranslationEstimator`, `BlockMatchingTranslationEstimator`, `FrameTranslation`, `FrameWarp`, `StabilizationTransform` | Search integer offsets within radius 0..64 using sampled luma difference; stabilization smooths consecutive offsets and translates by clamped sampling; retains history and resets on cut/start/end | [estimator](../src/main/java/io/synexia/chromellm/video/BlockMatchingTranslationEstimator.java), [temporal tests](../src/test/java/io/synexia/chromellm/video/TemporalPipelineTest.java) |
| `VideoAnalyzer`, `VideoAnalysisResult` | Decodes all frames but samples luma/cuts at `frameStride`; `framesAnalyzed` is total decoded count, not sampled count; compares successive sampled frames and reports cuts at original frame indices; owns source, writes no encoded output | [analyzer](../src/main/java/io/synexia/chromellm/video/VideoAnalyzer.java) |

`TransformSpecParser` splits a comma-separated pipeline and colon-separated parameters. It normalizes case and hyphens; supports pixel operations plus temporal-denoise, stabilization, unsharp, region, range, resize, crop and geometric aliases. Region/range syntax embeds a pixel operation, not an arbitrary nested pipeline. Some operation parsers consume only recognized positions, so extra fields are not universally rejected. Blank input, bad numbers, unknown enum names and constructor constraints fail with argument/parsing exceptions. See [parser source](../src/main/java/io/synexia/chromellm/video/TransformSpecParser.java) and [tests](../src/test/java/io/synexia/chromellm/video/TransformSpecParserTest.java).

Source-derived, in-memory example:

```java
try (var processor = new io.synexia.chromellm.gpu.CpuImageProcessor()) {
    var chain = new io.synexia.chromellm.video.TransformSpecParser(processor)
        .parsePipeline("resize:64:64,grayscale,unsharp:2:1.0:3");
    // Supply RGBA frames in order and apply the lifecycle when processing a stream.
}
```

## Frame interpolation

[`FfmpegVideoInterpolator`](../src/main/java/io/synexia/chromellm/video/FfmpegVideoInterpolator.java) accepts integer factors 2..8, multiplies output FPS by that factor, writes the first frame, then factor−1 intermediate frames and the next original frame for each pair. For N>0 decoded frames this is `1+(N−1)*factor`, not `N*factor`; it does not synthesize a tail after the last frame. The caller supplies and owns the vision processor. Java fallback is blending; OpenCV uses optical flow. Neither interpolation choice is a guarantee of motion quality or unchanged audio duration.

## Batch package

[`io.synexia.chromellm.batch`](../src/main/java/io/synexia/chromellm/batch) contains `BatchImageProcessor`, `BatchVideoProcessor`, immutable-list result `BatchProcessResult`, and `BatchFailure(input,message)`.

- Inputs are made absolute/normalized; missing input directories, identical input/output directories and nonpositive image parallelism fail before processing. Equality checks are lexical, not a symlink containment security boundary
- Discovery lists/walks regular files, filters supported extensions and sorts paths. Image formats: png/jpg/jpeg/bmp/gif. Video formats: mp4/mkv/mov/avi/webm/m4v. Discovery is completed before output creation; use a separate output tree to avoid later runs ingesting old outputs
- Relative paths are retained under the output root. Existing output with `overwrite=false` is recorded as a failure, not a skipped success. Parent directories are created. Writes are not transactional
- Each image invokes the transform supplier, then reset/start, apply at index zero, and end. Parallel image processing uses a fixed thread pool and waits for all tasks; the caller must return independently usable transforms and avoid unsafe shared native processors. The CLI limits its own unsafe sharing, but the lower-level API does not enforce that limit
- Video processing is sequential, creates one transform per source, and passes the borrowed processor to `FfmpegVideoProcessor`. Caught per-file exceptions become failure strings; in particular, the batch-video loop catches interruption as an ordinary item exception without explicitly restoring the thread interrupt flag
- `BatchProcessResult.failed()` is the failure-list size. Inspect it and CLI failure lines explicitly; a printed summary alone is not full success. [Image tests](../src/test/java/io/synexia/chromellm/batch/BatchImageProcessorTest.java) cover a recursive PNG write and same-directory rejection; [video test](../src/test/java/io/synexia/chromellm/batch/BatchVideoProcessorTest.java) only covers same-directory rejection

## Known verification boundaries

The source tests cover representative geometry, temporal resets, LUTs, masks, preset compilation and encoding-argument construction. They do not establish exhaustive codec/container compatibility, process cancellation, native resource safety under concurrency, arbitrary LUT validity, or performance. Native integration is opt-in; no test or media tool was executed while authoring this page. See the [ledger](DOCUMENTATION_COVERAGE.md) for remaining semantic and verification work.
