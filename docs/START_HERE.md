# ChromeLLM: browser-free Java/JNI entry points

The active inference module is **standalone/** (Java 21 JPMS, java.base only). ChromeML/Dawn, Node HTTP, PowerShell runners and Chrome-managed model paths have been removed from the draft product source. A licensed, independent GGUF model and compatible llama.cpp native library are required for real inference.

## Build and run the in-process JNI backend

```sh
mvn -f standalone/pom.xml verify
cmake -S standalone/native -B standalone/native/build \
 -DLLAMA_INCLUDE_DIR=/path/to/llama/include \
 -DGGML_INCLUDE_DIR=/path/to/llama/ggml/include \
 -DLLAMA_LIBRARY=/path/to/libllama.so
cmake --build standalone/native/build
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
 --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
 --backend jni --jni-lib /absolute/path/libchromellm_llama_jni.so \
 --model /path/to/licensed.gguf --prompt-file /path/to/request.txt \
 --max-tokens 256 --context-tokens 4096 --threads 4 --gpu-layers 0
```


Use --backend cli --llama-bin /absolute/path/llama-cli only when explicitly choosing the retained, browser-free process adapter. The CLI does not silently select JNI or download models.

## Local synthetic verification

```sh
cmake -S standalone/native -B standalone/native/build -DCHROMELLM_SYNTHETIC_TEST=ON
cmake --build standalone/native/build
mvn -f standalone/pom.xml test-compile
java -cp standalone/target/classes:standalone/target/test-classes \
 io.synexia.chromellm.standalone.JniSyntheticContractTestMain \
 /absolute/path/libchromellm_llama_jni.so
```


The fixture is test-only, **not a real language model**. Synthetic success does not verify GGUF loading, GPU operation, or model quality.

## Other source boundaries

The root pom.xml and CMakeLists.txt build a distinct Java/C++ diffusion, image, GPU, vision and video subsystem. The separate diffusion CLI no longer supports --prompt or --chrome-base; optional numeric conditioning uses exactly 16 finite values passed with --condition-vector.

Canonical refactor/deletion recipes belong in [com.synexia](https://github.com/hsoliwal/com.synexia/pull/14522); ChromeLLM only receives reviewable product files. See [migration record](BROWSER_REMOVAL_MIGRATION.md), [standalone README](../standalone/README.md) and [draft PR #19](https://github.com/hsoliwal/ChromeLLM/pull/19).
