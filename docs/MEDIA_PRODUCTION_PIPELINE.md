# Media Production Pipeline

This layer extends the existing Java 21 VirtualDub-style runtime with production workflow features while preserving the existing GPU, OpenCV, FFmpeg JNI/libav, process fallback, and diffusion backends.

## Goals

- keep ordinary image/video transforms local;
- keep input files immutable;
- decode once, apply a transform chain, encode once;
- make output encoding explicit instead of hard-coded;
- allow time-varying parameters without generative inference;
- support standard 3D LUT grading;
- make repeatable workflows portable through versioned JSON presets;
- report throughput and support cooperative cancellation.

## Configurable video encoding

`VideoEncodingOptions` supports:

- H.264 / `libx264`
- H.265 / `libx265`
- AV1 / `libaom-av1`
- VP9 / `libvpx-vp9`
- CRF
- encoder preset for H.264/H.265
- output pixel format
- audio copy, AAC, or no audio
- AAC bitrate
- FFmpeg thread count
- explicit extra FFmpeg arguments

Existing callers retain the historical defaults:

    H264 + veryfast + CRF 18 + yuv420p + audio copy

The encoder validates common chroma-subsampling geometry before launching FFmpeg. For example, `yuv420p` requires even width and height.

## Output geometry

Transforms may implement `FrameTransformShape` to declare output dimensions before encoding starts.

Implemented shape-changing transforms:

- `resize:width:height` - bilinear RGBA resize;
- `crop:x:y:width:height`;
- `rotate-90-cw`;
- `rotate-90-ccw`.

Shape-preserving geometry:

- `flip-horizontal`;
- `flip-vertical`;
- `rotate-180`.

`TransformChain` propagates dimensions sequentially, so this works:

    resize:1920:1080,crop:100:50:1600:900,rotate-90-cw

A dimension-changing transform is rejected when hidden inside a frame-range, masked, or region wrapper where output size could vary between frames.

## Keyframe automation

`KeyframeCurve` supports:

- HOLD
- LINEAR
- EASE_IN
- EASE_OUT
- EASE_IN_OUT

`KeyframedPixelTransform` evaluates p0..p3 independently for every frame and passes the resulting values to the existing `ImageProcessor`.

This means exposure, gamma, channel scaling, thresholding, and similar effects can change over time without invoking a model.

## Cube 3D LUTs

`CubeLutParser` and `Lut3dTransform` support 3D Adobe/Resolve-style `.cube` files:

- `LUT_3D_SIZE`
- `DOMAIN_MIN`
- `DOMAIN_MAX`
- `LUT_3D_INPUT_RANGE`
- comments and TITLE
- validated N^3 table size
- red-fastest table ordering
- trilinear interpolation
- 0..1 grading intensity mix

1D/shaper sections are rejected explicitly instead of being silently misread.

## Overlays

`OverlayImageTransform` uses the existing alpha-aware compositor. Presets can load PNG overlays once and apply them:

- at x/y coordinates;
- with opacity;
- for all frames or a configured frame range.

## Progress and cancellation

Programmatic video APIs accept:

- `ProcessingProgressListener`
- `ProcessingControl`

Progress reports:

- frames processed;
- elapsed duration;
- current aggregate processing FPS.

`VideoProcessResult` also exposes:

- `processingFramesPerSecond()`
- `realtimeFactor()`

Cancellation is cooperative and checked between decoded/output frames.

## JSON media preset v1

A preset is explicitly versioned:

    "version": 1

Relative LUT and overlay paths are resolved relative to the preset file.

Convenience sections are compiled in this deterministic order:

1. string `pipeline`;
2. optional LUT;
3. overlays;
4. keyframed automation.

The encoding section is independent of the transform pipeline.

See `docs/media-preset-v1.example.json`.

### Apply a preset to video

    mvn -q exec:java \
      -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
      -Dexec.args="video --input in.mp4 --output out.mp4 --preset cinematic.json"

Command-line encoder flags may override the preset:

    --codec h265
    --encoder-preset slow
    --crf 19
    --pixel-format yuv420p
    --audio aac
    --audio-bitrate 256
    --threads 8
    --progress-every 30

### Apply the same preset to an image

    mvn -q exec:java \
      -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
      -Dexec.args="image --input still.png --output graded.png --preset cinematic.json"

Frame automation evaluates at frame zero for a still image.

## Batch video

Batch video is non-destructive:

    mvn -q exec:java \
      -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
      -Dexec.args="batch-video --input-dir clips --output-dir processed --preset cinematic.json --decoder auto"

It:

- discovers common video containers;
- optionally walks recursively;
- preserves relative paths under a distinct output root;
- honors overwrite=false;
- reports per-file failures;
- reuses the selected local image processor;
- applies one decode/transform/encode pass per file.

## Interpolation

The existing OpenCV optical-flow interpolation path now also accepts the same configurable encoder settings, progress listener, and cancellation control.

## Cost boundary

Deterministic grading, overlays, resizing, cropping, rotation, filtering, stabilization, temporal denoise, batch processing, and codec work remain local.

A generative model is still only required when the edit must invent visual content that did not exist in the source, such as high-quality reconstruction behind a removed object. The pipeline keeps that inference localized rather than treating every frame as a cloud-generation request.
