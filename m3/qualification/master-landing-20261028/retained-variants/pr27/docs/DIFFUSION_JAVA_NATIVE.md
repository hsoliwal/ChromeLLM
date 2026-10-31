# Java / JNI / JNA Diffusion Integration

ChromeLLM provides a Java 21 diffusion subsystem independent of browser runtimes and text-model services.

Architecture:

    Explicit DiffusionRequest + DiffusionCondition
             |                |             |
          Java DDIM        JNA C ABI     JNI bridge
                              \             /
                           native C++ backend

Text inference is a separate, explicitly selected Java/JNI facility. The diffusion engine does not require a text model, browser, server, or model download.

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

## Explicit vector conditioning (no browser or server)

The current diffusion CLI does not parse a text prompt or call a model service.
Supply exactly 16 comma-separated finite floating-point values in [0,1] with
`--condition-vector`. A separate, independently selected local model or user
may prepare these values, but no inference service is started implicitly.

    mvn -q exec:java -Dexec.mainClass=io.synexia.chromellm.cli.Main -Dexec.args="--engine java --condition-vector 0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1.0,0.0,0.1,0.2,0.3,0.4,0.5 --output out.ppm"

Text inference and image generation remain separate workloads.

## Conditioning modes

- UNCONDITIONED: pure seeded noise.
- IMAGE: direct RGB structural conditioning.
- DEPTH: one-channel depth map.
- CLASS_LABEL: integer class conditioning.
- VECTOR: an explicit 16-value numeric conditioning array supplied by the caller.

## Native ABI

native/diffusion_native.h is the stable C ABI used by both JNA and JNI. It is intentionally backend-neutral.

The bundled Java and C++ implementations contain a deterministic DDIM-style scheduler plus a lightweight structural denoiser, which makes the complete Java/JNA/JNI pipeline executable without redistributing any model weights.

For photorealistic output, replace the Denoiser implementation or native backend with a trained U-Net/DiT runtime (ONNX Runtime, DirectML, CUDA, Vulkan, TensorRT, etc.) while preserving the API and ABI boundary.
