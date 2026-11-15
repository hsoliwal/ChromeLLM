# ChromeLLM: browser-free Java/JNI entry points

The active inference module is **standalone/** (Java 21 JPMS, `java.base` and `java.net.http`). It supports local llama.cpp CLI/JNI inference and explicit Gemini 4 Hydrogen API access. The local JNI provider can select native Dawn/WebGPU through llama.cpp. A browser, ChromeML, Node HTTP runner and Chrome-managed model files are not required.

## Choose the model provider

- For Hydrogen, set `GEMINI_API_KEY` and select `--backend gemini`; `--model gemini-4-hydrogen` and `--thinking-level high` are defaults. This sends the prompt to Google's API.
- For local inference, supply a licensed GGUF model and select `--backend cli` or `--backend jni`.
- For local WebGPU, build llama.cpp with its existing Dawn provider and select `--backend jni --compute-backend webgpu --gpu-layers 32`.

See the [standalone README](../standalone/README.md) for complete build commands, options and limits. Each provider is selected explicitly; the runtime never silently changes between local and remote inference.

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

Canonical recipes belong in [com.synexia](https://github.com/hsoliwal/com.synexia); ChromeLLM receives product code, product tests and documentation. The [migration record](https://github.com/hsoliwal/com.synexia/blob/develop/forks/ChromeLLM/docs/BROWSER_REMOVAL_MIGRATION.md) documents the earlier browser-removal work.
