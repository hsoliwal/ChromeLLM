# Java VirtualDub-Style Image and Video Pipeline

This layer turns the existing ChromeLLM GPU, OpenCV and FFmpeg primitives into a reusable Java 21 processing framework.

The model is intentionally simple:

    source frame
        |
        v
    TransformChain
        |
        +-- deterministic pixel/GPU transforms
        +-- region/range/mask transforms
        +-- temporal denoise
        +-- Java motion stabilization
        +-- OpenCV vision transforms when explicitly requested
        |
        v
    output frame

For video, FFmpeg or direct JNI/libav supplies frames and FFmpeg encodes them. The transform layer is the same Java API used for still images.

## Non-destructive invariant

Input images and videos are never modified in place by the batch or video processors. Output is written to a distinct path. Batch processing rejects identical input and output directories.

## Frame API

`FrameTransform` remains the compatibility boundary:

    RgbaFrame apply(RgbaFrame frame, long frameIndex)

Stateful transforms additionally implement `FrameTransformLifecycle`:

- `onStreamStart(VideoStreamInfo)`
- `reset()`
- `onStreamEnd()`

`TransformChain` propagates lifecycle calls in order and shuts down in reverse order. `FfmpegVideoProcessor` invokes the lifecycle once per stream.

This prevents temporal state from leaking from one video into the next.

## Pipeline parser

`TransformSpecParser` converts a compact pipeline string into Java transforms.

Pixel operations:

    grayscale
    gamma:1.1
    box-blur:2
    brightness-contrast:0.08:1.15
    channel-scale:1.05:0.98:0.95:1

Temporal denoise:

    temporal-denoise:currentWeight:cutThreshold:sampleStride

Example:

    temporal-denoise:0.25:0.35:4

Stabilization:

    stabilize:searchRadius:sampleStride:smoothing:cutThreshold

Example:

    stabilize:8:4:0.75:0.35

Unsharp mask:

    unsharp:radius:amount:threshold

Example:

    unsharp:2:1.2:3

Region-limited pixel transform:

    region:x:y:width:height:operation[:p0:p1:p2:p3]

Example:

    region:100:120:640:480:grayscale

Frame-range-limited pixel transform:

    range:startInclusive:endExclusive:operation[:p0:p1:p2:p3]

Example:

    range:300:600:gamma:1.15

Combine transforms with commas:

    temporal-denoise:0.25:0.35:4,unsharp:2:1.1:3,stabilize:8:4:0.75:0.35

## Scene cuts

`SceneCutDetector` combines:

- sampled mean absolute luma difference;
- normalized luma histogram distance.

The score is in approximately 0..1. Temporal denoise and stabilization reset when the configured scene-cut threshold is crossed, preventing state from bleeding across edits.

`VideoAnalyzer` exposes the same logic without rendering a new video.

Example:

    mvn -q exec:java \
      -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
      -Dexec.args="analyze-video --input movie.mp4 --cut-threshold 0.35 --sample-stride 4 --decoder auto"

Output includes frame count, dimensions, FPS, brightness statistics and detected cut frame indexes.

## Java stabilization

`BlockMatchingTranslationEstimator` performs sampled integer global-motion search over a configurable radius.

`StabilizationTransform`:

1. compares the current frame with the previous source frame;
2. estimates global dx/dy;
3. smooths translation over time;
4. warps the frame using edge-clamped sampling;
5. resets on scene cuts.

This is deliberately a deterministic Java fallback. OpenCV/native optical flow remains available for more sophisticated motion work.

## Mask and region processing

Reusable components:

- `FrameRegion`
- `FrameRegions.crop/paste`
- `RegionTransform`
- `FrameMaskProvider`
- `MaskedTransform`
- `AlphaMasks.invert`
- `AlphaMasks.threshold`
- `AlphaMasks.feather`
- `FrameRangeTransform`

These let semantic models produce only masks/regions while deterministic processors perform the actual pixel work.

## Color and sharpening

`UnsharpMaskTransform` uses the selected `ImageProcessor` for blur, then applies the high-frequency correction in Java.

`ColorMatrixTransform` implements a general 4x5 RGBA matrix, allowing custom color conversion and grading without adding one-off operations.

## Batch images

`BatchImageProcessor`:

- scans supported image types;
- optionally recurses through subdirectories;
- preserves relative paths below a separate output root;
- never writes to the input root;
- supports overwrite=false;
- reports per-file failures instead of silently dropping them;
- supports configurable parallelism.

CLI:

    mvn -q exec:java \
      -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
      -Dexec.args="batch-image --input-dir photos --output-dir processed --pipeline 'gamma:1.1,unsharp:2:1.0:3' --recursive true --parallelism 4"

The CLI serializes a hardware-accelerated processor context to avoid concurrently mutating one OpenCL kernel context. Pure Java CPU processing may use the requested parallelism.

## Video processing

Example full chain:

    mvn -q exec:java \
      -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
      -Dexec.args="video --input input.mp4 --output output.mp4 --pipeline 'temporal-denoise:0.25:0.35:4,unsharp:2:1.0:3,stabilize:8:4:0.75:0.35' --decoder auto"

Execution:

    input video
       |
       +--> JNI/libav decoder when available
       |       or
       +--> FFmpeg process decoder
               |
               v
           RGBA frame
               |
               v
        TransformChain
               |
               v
           RGBA frame
               |
               v
         FFmpeg encoder
               |
               +--> source audio stream when compatible
               |
               v
          output video

Only one decode/transform/encode pass is needed for a multi-stage transform chain.

## Existing native vision path

This Java pipeline composes with the existing:

- OpenCL JNA/JNI image processor;
- OpenCV JNI inpaint;
- OpenCV seamless clone;
- OpenCV GrabCut;
- OpenCV optical-flow interpolation;
- direct FFmpeg JNI/libav frame source;
- Java fallback implementations.

It does not duplicate those backends.

## What remains generative

Pixel transforms, compositing, stabilization, filtering, color work, masks and most restoration can run locally without cloud inference fees.

If an edit requires pixels that were never present in the source—for example revealing a complex background behind a removed object—high-quality reconstruction still requires an inpainting/generative prior. The architecture confines that inference to the masked region instead of regenerating the complete image or every complete video frame.
