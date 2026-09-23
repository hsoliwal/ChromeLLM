# Native Vision and Media Runtime

ChromeLLM's local media path now separates four concerns:

```text
FFmpeg/libav decoder
        |
        v
     RgbaFrame
        |
        +--> GPU/OpenCL deterministic transforms
        |
        +--> OpenCV vision operations
        |      - masks / GrabCut
        |      - Telea inpaint
        |      - seamless clone
        |      - optical flow
        |
        +--> optional diffusion/inpaint backend
        |
        v
FFmpeg encoder + original audio
```

No cloud service is required for any of the operations in this document.

## Design references

Two older public GitHub projects were inspected as architectural references:

- `jvl711/JavaFFmpegLibrary`: demonstrates Java wrappers around FFmpeg native objects such as format contexts, packets and frames.
- `tramvm/OpencvJni`: demonstrates Java/JNI/OpenCV integration.

Neither donor repository exposes an explicit repository-level license in the inspected tree, so no donor source has been copied into ChromeLLM. The implementation here is newly authored against the public OpenCV and FFmpeg APIs.

## OpenCV JNI

When OpenCV development libraries are available, CMake builds:

```text
chromellm_opencv_jni
```

The Java side uses owned RGBA and mask buffers rather than exposing native `cv::Mat*` pointers.

Implemented operations:

- **Telea inpaint**: fills a masked region from its surrounding pixels.
- **Seamless clone**: Poisson-style object insertion that blends local illumination and color.
- **GrabCut**: foreground-mask extraction from an initial rectangle.
- **Optical-flow interpolation**: estimates forward/backward Farneback flow and synthesizes intermediate frames.

The Java fallback remains available when OpenCV is not installed.

## Direct FFmpeg JNI decoder

When FFmpeg development libraries are available, CMake builds:

```text
chromellm_ffmpeg_jni
```

The decoder uses modern libav APIs:

- `avformat_open_input`
- `avformat_find_stream_info`
- `av_find_best_stream`
- `avcodec_send_packet` / `avcodec_receive_frame`
- `libswscale` conversion to RGBA
- `av_seek_frame` for seeking

Java consumes one `RgbaFrame` at a time, so whole videos are never loaded into memory.

`DecoderBackend.AUTO` prefers the JNI decoder and falls back to the existing FFmpeg process/pipe decoder if the native library is unavailable.

## Video transform chains

A video frame can pass through any number of deterministic transforms:

```text
frame
  -> grayscale
  -> contrast
  -> blur
  -> mask/object operation
  -> frame
```

CLI example:

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="video --input in.mp4 --output out.mp4 --pipeline grayscale,box-blur:2,gamma:1.1 --decoder auto"
```

## Local object removal

Fast approximation:

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="remove --input room.png --mask chair-mask.png --output out.png"
```

OpenCV inpaint:

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="inpaint --input room.png --mask chair-mask.png --radius 3 --output out.png --vision-backend opencv"
```

OpenCV inpaint reconstructs from surrounding image structure. A trained diffusion/inpaint model is still useful when the removed object hid complex semantic content that cannot be inferred from local pixels.

## Local object insertion

Simple alpha composition remains available through `place`.

For better visual integration, OpenCV seamless cloning can be used:

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="clone --input room.png --object chair.png --center-x 600 --center-y 500 --output out.png --vision-backend opencv"
```

## Foreground mask extraction

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="grabcut --input person.png --x 100 --y 40 --width 500 --height 800 --output mask.png --vision-backend opencv"
```

The resulting mask can feed GPU compositing, inpainting, background replacement, or a future diffusion backend.

## Local video frame generation

Optical-flow interpolation generates intermediate frames without a generative cloud model:

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="video-interpolate --input 30fps.mp4 --output 60fps.mp4 --factor 2 --decoder auto --vision-backend opencv"
```

A factor of 2 creates one intermediate frame between each original pair. Factors up to 8 are supported.

This is useful for frame-rate conversion and smoother motion. It does not invent arbitrary new scenes; it estimates motion between observed frames.

## Build

Base Java/GPU build:

```bash
mvn test
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
```

On Debian/Ubuntu, native vision/media development dependencies can be installed with:

```bash
sudo apt-get install pkg-config ffmpeg \
  libavformat-dev libavcodec-dev libavutil-dev libswscale-dev \
  libopencv-dev
```

CMake treats OpenCV and FFmpeg JNI as optional targets. The base runtime still builds when those development packages are absent.

## Licensing boundary

OpenCV 4.5+ is Apache-2.0 licensed. FFmpeg is primarily LGPL-2.1-or-later, with optional GPL components depending on how FFmpeg itself was configured.

ChromeLLM does not vendor OpenCV or FFmpeg binaries in this integration. It links to locally installed development/runtime libraries when available.
