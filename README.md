# Chrome2api

English | [简体中文](README.zh-CN.md)

OpenAI-compatible local API wrapper for Chrome Gemini Nano / ChromeML.

This project is an experimental runner and API server around Chrome's on-device
Gemini Nano runtime. It does not include Google model weights.

## What This Is

Chrome2api provides:

- A bare ChromeML runner source file.
- A local OpenAI-compatible HTTP API server.
- PowerShell wrappers for Windows.
- Reference Chromium / Dawn headers used to understand the ABI.

The intended runtime chain is:

```text
OpenAI-compatible client
  -> http://127.0.0.1:11435/v1/chat/completions
  -> runtime/chromeml_api_server.cjs
  -> runtime/ChromeMLBareRunner.exe
  -> optimization_guide_internal.dll
  -> webgpu_dawn.dll
  -> weights.bin
```

## What Is Not Included

The following file is intentionally not included:

```text
model/OptGuideOnDeviceModel/2025.8.8.1141/weights.bin
```

This file is not authored by this project. If you use this project locally,
you must provide it yourself from a compatible local Chrome / Chrome component
installation and comply with the relevant licenses and terms.

Do not upload this file to a public GitHub repository or redistribute it as
part of this project. It is large, proprietary, and subject to Google / Chrome
licensing terms.

## Add Local Model File

After cloning, copy your local model file into this exact path:

```text
Chrome2api/
  model/
    OptGuideOnDeviceModel/
      2025.8.8.1141/
        weights.bin
```

Typical source locations on a Windows machine with Chrome's on-device model
component installed may look like:

```text
%LOCALAPPDATA%\Google\Chrome\User Data\OptGuideOnDeviceModel\2025.8.8.1141\weights.bin
```

If the file is not there yet, trigger Chrome's built-in AI model download from a
local page, then wait until the download reaches 100%.

```powershell
$dir = "$env:TEMP\chrome2api-model-trigger"
New-Item -ItemType Directory -Force $dir | Out-Null
@'
<!doctype html>
<meta charset="utf-8">
<button id="run">Download Gemini Nano</button>
<pre id="log"></pre>
<script>
const log = (...args) => {
  document.getElementById("log").textContent += args.join(" ") + "\n";
};
document.getElementById("run").onclick = async () => {
  log("LanguageModel:", "LanguageModel" in self);
  if (!("LanguageModel" in self)) return;
  log("availability:", await LanguageModel.availability());
  await LanguageModel.create({
    monitor(m) {
      m.addEventListener("downloadprogress", e => {
        log("downloadprogress", e.loaded, e.total);
      });
    }
  });
  log("done");
};
</script>
'@ | Set-Content "$dir\index.html" -Encoding UTF8
Start-Process "C:\Program Files\Google\Chrome\Application\chrome.exe" "$dir\index.html"
```

After Chrome finishes, copy the downloaded model into this repository:

```powershell
robocopy "$env:LOCALAPPDATA\Google\Chrome\User Data\OptGuideOnDeviceModel\2025.8.8.1141" `
  ".\model\OptGuideOnDeviceModel\2025.8.8.1141" /E
```

## Expected Layout

After adding the local model file, the folder should look like:

```text
Chrome2api/
  runtime/
    ChromeMLBareRunner.exe
    ChromeMLBareRunner.cpp
    chromeml_api_server.cjs
    optimization_guide_internal.dll
    webgpu_dawn.dll
  model/
    OptGuideOnDeviceModel/
      2025.8.8.1141/
        weights.bin
  docs/
    OPENAI_COMPAT_API.md
  running scripts...
```

## Build Runner

The repository includes a prebuilt Windows runner. To rebuild it from source,
use Zig's bundled clang on Windows:

```powershell
.\scripts\build_runner.ps1
```

Or manually:

```powershell
zig c++ -target x86_64-windows-gnu -std=c++17 -O2 -municode `
  -Iruntime runtime\ChromeMLBareRunner.cpp `
  -o runtime\ChromeMLBareRunner.exe `
  -lole32 -lwindowscodecs -lpsapi
```

## Run Bare Runner

```powershell
.\运行裸Runner.ps1 "Say exactly OK."
```

Multi-image input is supported by repeating `-Image`:

```powershell
.\运行裸Runner.ps1 "Compare these images." `
  -Image C:\images\a.png `
  -Image C:\images\b.jpg
```

## Run API Server

```powershell
.\启动API服务.ps1 -HostAddress 127.0.0.1 -Port 11435
```

Then test:

```powershell
curl.exe http://127.0.0.1:11435/v1/models
```

```powershell
curl.exe --% -s http://127.0.0.1:11435/v1/chat/completions -H "Content-Type: application/json" -d "{\"model\":\"chrome-gemini-nano\",\"messages\":[{\"role\":\"user\",\"content\":\"Say exactly OK.\"}],\"max_tokens\":16}"
```

See [docs/OPENAI_COMPAT_API.md](docs/OPENAI_COMPAT_API.md) for API details.

## Current Capabilities

- Text prompt.
- Stateless multi-turn `messages`.
- Image input through local file paths or file URLs.
- Multiple image inputs at the runner layer.
- One audio file input at the runner layer.
- `max_tokens` mapped to output length.
- `temperature`, `top_k`, and context-token options passed to the runner.
- Non-streaming and simulated SSE streaming responses.

## Compatibility Notes

Chrome2api is not Chrome Prompt API itself. It is a local compatibility layer
around the lower ChromeML runtime. It does not implement browser permissions,
Origin Trial logic, DOM input objects, or Chrome profile model registration.

## Legal / Licensing

Project-authored source is released under the MIT License. Chromium and Dawn
reference files remain under their original BSD-style licenses. Google model
weights are not included and are not licensed by this project.

## 友情链接

- [LINUX.DO](https://linux.do/)


## Java / JNI / JNA Diffusion

An additive Java 21 diffusion subsystem is available in this fork. It runs independently of Gemini Nano, supports unconditioned, image, depth, class-label, and numeric-vector conditioning, and provides three execution paths:

- pure Java reference DDIM pipeline;
- JNA through a stable portable C ABI;
- JNI through the same native C++ backend.

The existing ChromeLLM/OpenAI-compatible server can optionally act as a local scene-conditioning planner; the diffusion engine itself does not require an LLM.

See [docs/DIFFUSION_JAVA_NATIVE.md](docs/DIFFUSION_JAVA_NATIVE.md).


## Local GPU Image / Video Processing

The fork also contains a Java 21 image/video processing layer that treats decoded images and video frames as RGBA pixel buffers and executes deterministic transformations locally.

Execution backends:

- OpenCL GPU through JNA and a stable C ABI;
- OpenCL GPU through JNI;
- pure Java CPU fallback;
- `AUTO` selection that prefers a GPU and falls back safely.

Implemented operations include color transforms, gamma, thresholding, channel scaling, Sobel edges, blur, alpha-aware object compositing, procedural image generation, masked removal approximation, and FFmpeg frame streaming for whole-video processing.

This is deliberately complementary to diffusion: ordinary pixel work stays local and cheap; learned inpainting/generation is only needed when the program must invent visual content that is not present in the source pixels.

See [docs/GPU_IMAGE_VIDEO.md](docs/GPU_IMAGE_VIDEO.md).


## Native OpenCV + FFmpeg JNI

The local image/video runtime also supports optional native vision and media backends:

- OpenCV JNI for Telea inpaint, seamless cloning, GrabCut masks, and optical-flow interpolation.
- Direct FFmpeg JNI/libav decoding to one RGBA frame at a time.
- `DecoderBackend.AUTO` prefers JNI and falls back to the FFmpeg process/pipe path.
- `TransformChain` applies multiple frame transforms in one decode/encode pass.
- Optical-flow video interpolation generates additional frames locally.

See [docs/NATIVE_VISION_MEDIA.md](docs/NATIVE_VISION_MEDIA.md).


## Java VirtualDub-Style Pipeline

The local media runtime now includes a higher-level Java 21 processing pipeline over the existing GPU/OpenCV/FFmpeg primitives.

It adds:

- lifecycle-aware `TransformChain` execution;
- scene-cut detection and video analysis;
- temporal denoise with automatic cut resets;
- pure-Java block-matching motion estimation and stabilization;
- unsharp masking and general RGBA color matrices;
- frame-region, mask and frame-range transforms;
- mask invert/threshold/feather utilities;
- recursive non-destructive batch image processing;
- reusable transform-spec parsing for both images and video.

Example:

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="video --input in.mp4 --output out.mp4 --pipeline 'temporal-denoise:0.25:0.35:4,unsharp:2:1.0:3,stabilize:8:4:0.75:0.35' --decoder auto"
```

See [docs/VIRTUALDUB_JAVA_PIPELINE.md](docs/VIRTUALDUB_JAVA_PIPELINE.md).


## Production Media Presets and Encoding

The Java media runtime now also includes a production workflow layer:

- configurable H.264, H.265, AV1 and VP9 encoding;
- audio copy, AAC, or no-audio output;
- progress callbacks, cooperative cancellation, processing FPS and realtime-factor metrics;
- dimension-changing resize/crop/90-degree rotation with stream-shape propagation;
- frame-preserving flips and 180-degree rotation;
- Adobe/Resolve-style 3D `.cube` LUT grading with trilinear interpolation;
- reusable alpha-aware image overlays;
- HOLD/LINEAR/ease-in/ease-out/ease-in-out keyframe automation;
- versioned JSON media presets with relative LUT/asset paths;
- non-destructive recursive batch video processing;
- configurable encoding/progress for optical-flow interpolation as well as ordinary video processing.

Example preset processing:

```bash
mvn -q exec:java \
  -Dexec.mainClass=io.synexia.chromellm.gpu.cli.GpuMain \
  -Dexec.args="video --input in.mp4 --output out.mp4 --preset cinematic.json --codec h265 --crf 20"
```

See [docs/MEDIA_PRODUCTION_PIPELINE.md](docs/MEDIA_PRODUCTION_PIPELINE.md) and [docs/media-preset-v1.example.json](docs/media-preset-v1.example.json).
