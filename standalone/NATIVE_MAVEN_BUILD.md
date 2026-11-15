# Maven-owned native JNI builds

ChromeLLM's browser-free text runtime is Java 21 plus a separately supplied, license-reviewed llama.cpp implementation. The Maven profiles below drive the existing native CMake project; they do **not** use Chrome's browser APIs, bundle ChromeML binaries, fetch model weights, download an upstream checkout, or claim parity with Chrome-managed AI. They are product build entry points, not Synexia source-transformation recipes.

## Select one explicit native source

A native fork is an implementation input, not a Maven runtime dependency. For example, an operator with access to `hsoliwal/synexia-llmcpp` may separately check out that repository and record its exact commit before building:

```sh
git -C /absolute/path/to/synexia-llmcpp rev-parse HEAD
mvn -B -ntp -f standalone/pom.xml -Pnative-fork \
  -Dchromellm.native.source=/absolute/path/to/synexia-llmcpp verify
```

`native-fork` requires `chromellm.native.source`; an absent/blank value fails in Maven's validate phase. CMake also checks the required `llama.h` and `ggml-backend.h` source layout. Java contracts compile and run with the same Maven build. Maven writes the native build tree to `standalone/target/native-fork`, never inside the donor checkout, and does not mutate or fetch the donor. On Windows, use an absolute path accepted by CMake.

For a qualified Dawn/WebGPU source build, explicitly opt in:

```sh
mvn -B -ntp -f standalone/pom.xml -Pnative-fork \
  -Dchromellm.native.source=/absolute/path/to/synexia-llmcpp \
  -Dchromellm.native.webgpu=ON verify
```

The supplied checkout must actually support GGML WebGPU and CMake must be able to find a compatible Dawn package (for example, through the operator's `CMAKE_PREFIX_PATH` or `Dawn_DIR`). There is no silent GPU-to-CPU substitution when WebGPU is explicitly requested. No native model or device inference test runs automatically because a licensed GGUF and a supported device are operator-specific. The normal `--jni-lib` and `--model` runtime parameters remain mandatory.

## Synthetic JNI test build

For CI and contract development without a model or donor checkout:

```sh
mvn -B -ntp -f standalone/pom.xml -Pnative-synthetic verify
```

This builds the existing **test-only** JNI stub into `standalone/target/native-synthetic`, runs its CTest library-discovery regressions, and runs the Java contract suite. On Unix, the JNI bridge is `libchromellm_llama_jni.so` (Linux) or `libchromellm_llama_jni.dylib` (macOS); on Windows it is normally under `Release/chromellm_llama_jni.dll`. GitHub Actions separately invokes `JniSyntheticContractTestMain` with that exact library, including cancellation and allocation-limit checks. Neither the synthetic profile nor its CI checks assert real model correctness.

## Admission and boundaries

- The default `mvn -f standalone/pom.xml verify` is unchanged: Java-only, no CMake, network, model or native build prerequisite.
- The two native profiles are opt-in and mutually exclusive. Maven's `validate` stage must fail closed when `native-fork` and `native-synthetic` are activated together, **before** CMake or any native artifact writes. A single profile must not silently substitute for the other.
- The operator pins and audits the donor source, native dependencies and model licenses. The local build does not copy native artifacts or weights into the Java JAR.
- Source-build acceptance requires an independently supplied real model, runtime observation, licensing review, the supported OS and (when selected) measured WebGPU device execution. A passing Maven/CTest/synthetic JNI build does **not** supply these proofs.
- Canonical source mutation, qualification receipts and evidence belong in `hsoliwal/com.synexia`, not ChromeLLM. M3JDK21 integration remains a separate, explicitly qualified receiving change.
