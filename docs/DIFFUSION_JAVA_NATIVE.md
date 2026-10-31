# Java 21 reference diffusion / JNI media

The diffusion/media subsystem is independent of the text-model JNI module. It does not use Chrome, a browser model API or Gemini Nano weights. The included reference denoiser is not a trained photorealistic image model.

```sh
mvn -B test
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel 2
mvn -q exec:java -Dexec.mainClass=io.synexia.chromellm.cli.Main \
 -Dexec.args="--engine java --width 256 --height 256 --steps 40 --seed 7 --output out.ppm"
```


The diffusion CLI supports java, jna or jni compute engines and an optional --condition-vector containing exactly 16 finite comma-separated values between 0 and 1. The removed --prompt and --chrome-base arguments are no longer accepted. Any text-to-conditioning workflow must explicitly invoke the separate, independently licensed standalone ModelBackend, validate its result, then construct a DiffusionCondition.

Native media components in root native/ are not the text inference library. See [native runtime contracts](NATIVE_RUNTIME_CONTRACTS.md), [standalone text JNI](../standalone/README.md) and [migration record](BROWSER_REMOVAL_MIGRATION.md).
