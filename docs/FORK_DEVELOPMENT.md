# Developing ChromeLLM without Chrome

Use Java 21 and C++17. Standalone text inference lives in **standalone/** and is independent of browser installation, ChromeML DLLs, Dawn, Node, PowerShell and Chrome-managed model files. A separate licensed llama.cpp/GGUF runtime is an operator-supplied input.

## Two build units

- Text inference: standalone/pom.xml, standalone/native/CMakeLists.txt, ModelBackend, LlamaCppJniBackend, StandaloneMain and the synthetic JNI contract test.
- Diffusion/media: root pom.xml, root CMakeLists.txt and native/. Media has its own JNA/Jackson and native OpenCL/OpenCV/FFmpeg dependencies; they are not transitive Java runtime dependencies of standalone/.

```sh
mvn -B -ntp -f standalone/pom.xml verify
jdeps --print-module-deps standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar
cmake -S standalone/native -B standalone/native/build \
 -DLLAMA_INCLUDE_DIR=/path/to/llama/include \
 -DGGML_INCLUDE_DIR=/path/to/llama/ggml/include \
 -DLLAMA_LIBRARY=/path/to/libllama.so
cmake --build standalone/native/build
```


Select --backend jni with explicit --jni-lib and --model paths. The retained llama.cpp CLI provider is a separate browser-free subprocess adapter selected with --backend cli.

## Acceptance and rights

GitHub Actions performs Java-only closure, checks absence of deleted runtime files and ChromeML/Dawn native linkage, and builds the synthetic JNI fixture. This does not establish real licensed model inference, GPU, Windows or macOS qualification. Native binaries and model weights are not bundled. Maintain donor licenses and provenance. Canonical source transformations belong to [Synexia](https://github.com/hsoliwal/com.synexia/pull/14522).

See [START_HERE](START_HERE.md), [diffusion/media](DIFFUSION_JAVA_NATIVE.md), [JNI source](../standalone/native/llama_jni.cpp) and [PR #19](https://github.com/hsoliwal/ChromeLLM/pull/19).
