# ChromeLLM — standalone Java/JNI inference

ChromeLLM now separates its independent Java 21 model runtime from Chrome2api/ChromeML. **The source product no longer requires browser APIs, Node/PowerShell runners, ChromeML/Dawn DLLs or Chrome-managed model weights for text inference.** Historical fork attribution remains in LICENSE, NOTICE, THIRD_PARTY.md and Git history.

- [standalone/](standalone/README.md) is a separately built `java.base`-only module with two explicitly selected backends: subprocess `llama.cpp` CLI, and new **in-process llama.cpp JNI**. The operator supplies a licensed model and native engine.
- `src/main/java/io/synexia/chromellm/` owns separate diffusion/GPU/vision/video/media functionality; the diffusion CLI no longer calls Chrome.
- `native/` and the root CMake build contain media JNI adapters; `standalone/native/` contains the independent llama.cpp JNI library with a test-only synthetic backend.

Run `mvn -f standalone/pom.xml verify` to compile the Java module and its CLI contract tests. Build the JNI library from `standalone/native/` against an externally supplied, compatible llama.cpp build. The standalone main accepts `--backend jni --jni-lib /absolute/path/libchromellm_llama_jni.so --model /path/to/licensed.gguf --prompt-file /path/to/request.txt`. See [standalone README](standalone/README.md) and [extraction receipt](docs/BROWSER_REMOVAL_MIGRATION.md).

A passing synthetic JNI test proves binding/lifecycle behavior only, not trained model correctness, GPU acceleration, or OS qualification. Older ChromeML documentation is historical. Transform recipes and fixed-point source qualification are owned by hsoliwal/com.synexia; downstream M3jdk21 imports only separately qualified implementation outputs.
