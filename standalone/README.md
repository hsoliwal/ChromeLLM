<!-- SPDX-License-Identifier: MIT -->
# Standalone Java 21 + JNI inference

This module is **browserless**. There is no Chrome/Chromium browser integration,
ChromeML or Dawn/WebGPU binary, Chrome Prompt API, Node server, PowerShell launcher,
Chrome user profile, or Google model dependency in this runtime.

The same Java 21 `ModelBackend` contract supports two explicitly selected providers:

- **`JniLlamaCppBackend`**: in-process native inference through the C++17 JNI
  bridge in [native/](native/README.md), linked to the exact independently
  licensed `llama.cpp` source revision documented there. The first release
  candidate is CPU-only.
- **`LlamaCppCliBackend`**: an optional independent `llama-cli` subprocess,
  requiring an operator-supplied executable and a compatible local GGUF model.
  It is not a hidden fallback for JNI failures.

Neither provider downloads model weights. The user/operator must supply a
compatible, legally usable GGUF model, and verify the model's license,
checkpoint, tokenizer/config and provenance independently. No proprietary
Chrome/Gemini Nano weights or DLLs may be redistributed.

## Java and synthetic JNI verification

JDK 21, Maven, CMake and a C++17 compiler are required to run all local checks.

```sh
mvn -f standalone/pom.xml verify
jdeps --print-module-deps standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar

cmake -S standalone/native -B build/jni-fixture -DCHROMELLM_JNI_CONTRACT_FIXTURE=ON
cmake --build build/jni-fixture
java -Xcheck:jni -cp standalone/target/classes:standalone/target/test-classes \
  io.synexia.chromellm.standalone.JniContractTestMain \
  "$PWD/build/jni-fixture/libchromellm_llama_jni_fixture.so"

jmod create --class-path standalone/target/classes io.synexia.chromellm.standalone.jmod
jmod describe io.synexia.chromellm.standalone.jmod
```

The Java module depends on `java.base` only. Native library loading is explicit;
a Java API instance need not load JNI until the JNI provider is constructed.
The synthetic JNI fixture verifies encoding, byte-preserving output, lifecycle,
timeouts/error propagation and closure. It is **not** a trained model, and must
not be shipped as a real inference engine.

## Running independently with a real licensed model

The following requires an externally checked-out, exact pinned `llama.cpp`
revision, a compatible GGUF file and your own native build:

```sh
cmake -S standalone/native -B build/jni-real \
  -DLLAMA_CPP_SOURCE_DIR=/path/to/pinned/llama.cpp
cmake --build build/jni-real --target chromellm_llama_jni --parallel

java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.JniStandaloneMain \
  --jni-library "$PWD/build/jni-real/libchromellm_llama_jni.so" \
  --model /path/to/licensed-model.gguf \
  --prompt-file /path/to/prompt.txt --max-tokens 256 --timeout-seconds 120
```

The `llama.cpp` revision is **889f2ec96deacbc2d8a9a67dbe181eaa43a2fb3b**,
and CMake refuses a different HEAD. Adjust the platform-specific native
library filename for Windows (`.dll`) or macOS (`.dylib`). Do not assume
that a successful synthetic test establishes real GGUF compatibility.

The older explicitly selected `StandaloneMain --llama-bin ...` interface
remains available for subprocess compatibility; it does not use Chrome.
Java prompt requests are bounded, ill-formed UTF-8 is refused, and model
candidate output is preserved without trimming whitespace. No generated
code is executed or promoted automatically. Native deadlines are cooperative
during model evaluation; slow model loading and every blocking native kernel
are **not** guaranteed to be preemptible.

## Qualification and ownership

This is a **draft implementation**, not a verified Gemini Nano replacement.
Before release it requires actual canonical Synexia Maven/OpenRewrite recipe
execution, fixed-point/source-drift refusal evidence, a pinned and licensed
GGUF real-model CPU test, Java/JNI and OS-specific integration, distribution
rights audit, and independent receiving-product tests. GPU/APU, model
streaming, cancellation parity and throughput remain unqualified.

The source-sealed recipe, donor comparison, fixtures and admission receipts
belong exclusively in private `hsoliwal/com.synexia`. ChromeLLM receives only
qualified implementation postimages, product tests, and passive provenance.
