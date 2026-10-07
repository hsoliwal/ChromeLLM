# GPU Image and Video Runtime

ChromeLLM includes a local pixel-processing runtime in addition to the existing ChromeML and diffusion layers.

## Why this exists

Images and decoded video frames are arrays of RGBA pixels. Operations that are deterministic functions of those pixels do not need an LLM and usually do not need a generative model.

The runtime therefore separates:

1. **Intent / semantics** - optional Chrome AI or another model can decide what should happen.
2. **Masks / structure** - object masks, depth, regions and coordinates describe where it should happen.
3. **Execution** - OpenCL kernels perform the pixel work locally on a GPU.
4. **Generative repair** - diffusion/inpainting is reserved for areas that genuinely require new visual content.

That lets the inexpensive deterministic path handle most of a frame while a trained model, when present, only handles the hard masked region.

## Backends

The Java API is backend-neutral:

- `CPU` - pure Java fallback.
- `JNA` - Java -> stable C ABI -> dynamically loaded OpenCL runtime.
- `JNI` - Java -> JNI -> the same native OpenCL runtime.
- `AUTO` - prefers JNA/OpenCL GPU, then JNI/OpenCL GPU, then Java CPU.

No OpenCL SDK headers are required to compile the native library. The runtime loads the installed OpenCL implementation dynamically:

- Windows: `OpenCL.dll`
- Linux: `libOpenCL.so.1` / `libOpenCL.so`
- macOS: system OpenCL framework

## Implemented GPU pixel operations

- copy
- invert
- grayscale
- brightness + contrast
- gamma
- threshold
- independent RGBA channel scaling
- Sobel edge detection
- box blur
- alpha-aware compositing
- procedural solid / gradient / noise / plasma / checkerboard generation

## Object placement

RGBA objects can be positioned over a background:

```text
background + object RGBA + (x,y) + opacity
                    |
                    v
             alpha compositor
                    |
                    v
                  image
```

The object's existing alpha channel is respected.

## Object removal

`MaskOps.removeApprox` takes a mask and reconstructs the masked region with repeated local blur/fill passes. This is fast and fully local, but it is intentionally described as an approximation.

For a simple background, that may be enough. For a complex object covering previously invisible content, no pixel transform can know the exact hidden scene. The same mask can instead be sent to a future diffusion/inpainting backend while the rest of the frame remains unchanged.

## Video pipeline

Video uses FFmpeg only for codec/container work:

```text
input video
    |
    v
FFmpeg decode
    |
    v
raw RGBA frame
    |
    v
ImageProcessor (OpenCL GPU / JNI / JNA / CPU)
    |
    v
raw RGBA frame
    |
    v
FFmpeg encode + original audio when compatible
    |
    v
output video
```

The processor sees exactly the same `RgbaFrame` API for a PNG and for frame 50,000 of a video.

Set alternate executables when needed:

```text
-Dchromellm.ffmpeg=/path/to/ffmpeg
-Dchromellm.ffprobe=/path/to/ffprobe
```

## Build

```bash
mvn test
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
```

For JNI/JNA at runtime, put the generated native libraries on the platform library path.

## CLI

Inspect selected backend:

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="info --backend auto"
```

Process an image:

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="image --input in.png --output out.png --op sobel-edge --backend auto"
```

Generate pixels directly on the processor:

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="generate --output plasma.png --width 1024 --height 1024 --generator plasma --seed 42 --backend auto"
```

Place a transparent object:

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="place --input room.png --object chair.png --x 350 --y 420 --opacity 1 --output room-chair.png --backend auto"
```

Approximate masked removal:

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="remove --input room.png --mask chair-mask.png --radius 8 --passes 4 --output room-no-chair.png --backend auto"
```

Process every frame of a video:

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="video --input input.mp4 --output output.mp4 --op grayscale --backend auto"
```

## Cost model

This path avoids per-image/per-video cloud inference charges for deterministic operations. Local cost becomes primarily electricity, GPU time, storage and codec work.

It does **not** imply that arbitrary photorealistic text-to-image or text-to-video can be obtained from pixel shaders alone. Those tasks require learned visual priors. The architecture keeps those expensive learned operations optional and localized instead of making them the default for every pixel of every frame.
