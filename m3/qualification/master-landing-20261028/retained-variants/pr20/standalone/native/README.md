# In-process Java/JNI llama.cpp provider

**No browser and no ChromeML/Dawn runtime.** `chromellm_llama_jni.cpp`
implements `nativeOpen`, `nativeInfer` and `nativeClose` for
`io.synexia.chromellm.standalone.JniLlamaCppBackend`.

## Synthetic JNI ABI fixture (no model, not for distribution)

```sh
cmake -S standalone/native -B build/jni-fixture -DCHROMELLM_JNI_CONTRACT_FIXTURE=ON
cmake --build build/jni-fixture
mvn -f standalone/pom.xml verify
java -cp standalone/target/classes:standalone/target/test-classes \
  io.synexia.chromellm.standalone.JniContractTestMain \
  "$PWD/build/jni-fixture/libchromellm_llama_jni_fixture.so"
```

Java 21 and a C++17 compiler are required; set `JAVA_HOME` to your JDK when
CMake cannot find JNI. On Windows use the built fixture `.dll` filename;
on macOS use `.dylib`. This tests JNI transport only, not inference.

## Real licensed native provider (separate qualification)

```sh
git clone https://github.com/ggml-org/llama.cpp.git /path/to/llama.cpp
git -C /path/to/llama.cpp checkout 889f2ec96deacbc2d8a9a67dbe181eaa43a2fb3b
cmake -S standalone/native -B build/jni-release \
  -DLLAMA_CPP_SOURCE_DIR=/path/to/llama.cpp
cmake --build build/jni-release --parallel
mvn -f standalone/pom.xml verify
java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.JniStandaloneMain \
  --jni-library "$PWD/build/jni-release/libchromellm_llama_jni.so" \
  --model /path/to/licensed-model.gguf --prompt-file /path/to/prompt.txt
```

This CMake project refuses a checkout whose HEAD is not the exact pinned commit.
It does not download model weights or Chrome binaries. The CPU-only adapter
loads the GGUF model once per instance; synchronous `infer` and `close` calls
are serialized. Deadline/interrupt checks run between native inference steps,
not as OS-level preemption of a stalled kernel or slow model load.

## ABI contract and negative test

Java 21 expects JNI ABI version `1` before opening any external GGUF model.
A native library exposing a different version or no version entrypoint is
rejected without a browser or provider fallback. The negative test uses a
synthetic library with ABI version 2 in a separate JVM:

```sh
cmake -S standalone/native -B build/jni-abi-negative \
  -DCHROMELLM_JNI_CONTRACT_FIXTURE=ON \
  -DCHROMELLM_JNI_ABI_MISMATCH_FIXTURE=ON
cmake --build build/jni-abi-negative
java -Xcheck:jni -cp standalone/target/classes:standalone/target/test-classes \
  io.synexia.chromellm.standalone.JniContractTestMain \
  "$PWD/build/jni-abi-negative/libchromellm_llama_jni_abi_mismatch_fixture.so" \
  --expect-abi-mismatch
```

This proves fail-closed version negotiation, not actual model inference.

## Java JMOD

```sh
jmod create --class-path standalone/target/classes \
  io.synexia.chromellm.standalone.jmod
jmod describe io.synexia.chromellm.standalone.jmod
```

A Java-only JMOD retains a provider-neutral API and loads a separate
operator-supplied native library. A future distributable JMOD may include
qualified platform-specific libraries via `jmod --libs`; no synthetic fixture
or proprietary assets may be packaged. GPU/APU support, real inference,
cancellation, cross-platform testing and performance proof remain pending.
