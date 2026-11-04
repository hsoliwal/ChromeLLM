# Java / JNI / JNA Diffusion Integration

ChromeLLM now contains an additive Java 21 diffusion subsystem beside the existing ChromeML runner.

Architecture:

    existing ChromeLLM / ChromeML :11435
                   |
             optional planner
                   v
    DiffusionRequest + DiffusionCondition
          |             |             |
       Java DDIM      JNA C ABI      JNI bridge
                         \             /
                      native C++ backend

The diffusion engine does not require Gemini Nano or any text model. ChromeLLM is only an optional conditioning planner.

## Build

Java:

    mvn test
    mvn package

Native:

    cmake -S . -B build
    cmake --build build --config Release

## No-LLM generation

    mvn -q exec:java -Dexec.mainClass=io.synexia.chromellm.cli.Main -Dexec.args="--engine java --width 256 --height 256 --steps 40 --seed 7 --output out.ppm"

Use --engine jna or --engine jni after building the native libraries.

## ChromeLLM-conditioned generation

Start the existing ChromeLLM API server on 127.0.0.1:11435, then run:

    mvn -q exec:java -Dexec.mainClass=io.synexia.chromellm.cli.Main -Dexec.args="--engine java --prompt 'deep navy velvet sofa, cinematic window light' --output out.ppm"

The local Gemini Nano path produces a compact numeric scene vector. Image generation remains a separate diffusion pipeline.

## Conditioning modes

- UNCONDITIONED: pure seeded noise.
- IMAGE: direct RGB structural conditioning.
- DEPTH: one-channel depth map.
- CLASS_LABEL: integer class conditioning.
- VECTOR: arbitrary numeric conditioning, including ChromeLLM-generated scene vectors.

## Native ABI

native/diffusion_native.h is the stable C ABI used by both JNA and JNI. It is intentionally backend-neutral.

The bundled Java and C++ implementations contain a deterministic DDIM-style scheduler plus a lightweight structural denoiser, which makes the complete Java/JNA/JNI pipeline executable without redistributing any model weights.

For photorealistic output, replace the Denoiser implementation or native backend with a trained U-Net/DiT runtime (ONNX Runtime, DirectML, CUDA, Vulkan/Dawn, TensorRT, etc.) while preserving the API and ABI boundary.
