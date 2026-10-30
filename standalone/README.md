# ChromeLLM standalone Java 21 inference

The module has **no Chrome browser, ChromeML, Dawn, Node, PowerShell or third-party Java runtime dependency**. An explicit provider is either the browser-free llama.cpp CLI adapter or the new **in-process llama.cpp JNI adapter**. The operator supplies a lawfully licensed GGUF model and native engine. The JNI code never downloads, extracts or falls back to Chrome assets.

## Build JNI from an independently licensed llama.cpp

Reference fork: hsoliwal/synexia-llmcpp at commit 650ac3739d993e1d425f2d5db3c4205b3606cc30. The build's library headers and ABI must be compatible; this repository does not vendor llama.cpp or weights.

On Linux:


```sh
mvn -f standalone/pom.xml verify
cmake -S standalone/native -B standalone/native/build \
  -DLLAMA_INCLUDE_DIR=/path/to/llama/include -DLLAMA_LIBRARY=/path/to/libllama.so
cmake --build standalone/native/build --parallel
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --backend jni --jni-lib /absolute/path/libchromellm_llama_jni.so \
  --model /path/to/licensed.gguf --prompt-file /path/to/request.txt \
  --context-tokens 4096 --threads 4 --gpu-layers 0 --max-tokens 256
```

On Windows/macOS, build the platform DLL/dylib and provide correct loader paths for its transitive dependencies. `--backend cli --llama-bin /path/to/llama-cli` remains available. No automatic provider fallback is permitted.

The JNI provider supports decoder-only text models. It uses one model handle per Java backend, a fresh context per bounded call, deterministic greedy decoding, checked UTF-8 and SHA-256 prompt receipts. Per-instance generation and close serialize in Java; native handles are registry-validated. The deadline uses periodic checks and llama.cpp's CPU abort callback; long GPU kernels may not be interruptible immediately. The `--gpu-layers` argument requests offload but cannot prove actual device execution.

## Synthetic JNI test (not a real model run)

```sh
cmake -S standalone/native -B /tmp/chromellm-jni-test -DCHROMELLM_SYNTHETIC_TEST=ON
cmake --build /tmp/chromellm-jni-test
mvn -f standalone/pom.xml test-compile
java -cp standalone/target/classes:standalone/target/test-classes \
 io.synexia.chromellm.standalone.JniSyntheticContractTestMain \
 /tmp/chromellm-jni-test/libchromellm_llama_jni.so
```

This test checks JNI loading, UTF-8 input, bounded output, prompt receipt, timeout, close and stale-handle rejection using a **fake test-only llama API**. Never ship this fixture in production. Real GGUF inference, GPU offload, model quality, and Windows/macOS qualification remain unverified. Reusable source-change recipes and fixed-point evidence belong to hsoliwal/com.synexia; product code and tests live here.
