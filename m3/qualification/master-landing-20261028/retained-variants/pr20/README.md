# ChromeLLM: browserless Java 21 + native inference

ChromeLLM's current product direction is an **in-process local inference engine**
with no Chrome/Chromium browser, DOM, extensions, browser APIs, Node.js,
PowerShell, ChromeML, Dawn/WebGPU or Google runtime/model dependency.

## Inference runtime

- `standalone/` is the Java 21 JPMS module `io.synexia.chromellm.standalone`
  with a `java.base`-only runtime dependency closure.
- `JniLlamaCppBackend` loads a separately compiled native C++17 JNI provider,
  retaining model ownership and running CPU text inference in-process.
- `JniStandaloneMain` is the Java CLI entrypoint for the JNI backend.
- `LlamaCppCliBackend` is an existing **browserless** out-of-process fallback
  for explicitly operator-supplied `llama-cli`. It has no Chrome dependency.
- A compatible, lawfully licensed GGUF model is required and is NEVER bundled.
- Native code relies on pinned `ggml-org/llama.cpp` commit
  `889f2ec96deacbc2d8a9a67dbe181eaa43a2fb3b`. See
  [native build guide](standalone/native/README.md).

```text
Java/M3String API
      |
ModelRequest / ModelBackend / ModelResponse  (Java 21)
      |
JniLlamaCppBackend -> JNI C++17 -> llama.cpp -> licensed GGUF model
      |
CPU baseline; GPU/APU future opt-in after qualification
```

## Build and test

```sh
mvn -f standalone/pom.xml verify
cmake -S standalone/native -B build/chromellm-jni-test -DCHROMELLM_JNI_CONTRACT_FIXTURE=ON
cmake --build build/chromellm-jni-test
java -cp standalone/target/classes:standalone/target/test-classes \
  io.synexia.chromellm.standalone.JniContractTestMain \
  "$PWD/build/chromellm-jni-test/libchromellm_llama_jni_fixture.so"
jmod create --class-path standalone/target/classes io.synexia.chromellm.standalone.jmod
```

The fixture validates JNI transport, UTF-8, receipt hashes, errors and lifecycle;
it is **not** live model inference and must never be distributed as the provider.
Real model tests and Windows/macOS/accelerator parity are pending.

## Additional independent Java/native projects

The repository also contains independent Java 21 image/video media, diffusion,
OpenCL and optional OpenCV/FFmpeg JNI modules. The root CMake/Maven builds for
those independent workloads remain separate and must be contract-tested.

## Source and licensing

- Legacy ChromeML/Dawn/Node/PowerShell runtime sources and tracked proprietary
  DLLs are proposed for removal from the **current Git tree**. Historical Git
  objects may retain copies and must be audited before redistribution; deleting
  a path does not erase Git history.
- Chrome model assets and Google/Chrome runtime DLLs are neither required nor
  authorized by the browserless standalone module.
- Upstream licenses/attribution remain applicable to retained native/media code.
  See [THIRD_PARTY.md](THIRD_PARTY.md) and [NOTICE](NOTICE).
- Canonical Maven/OpenRewrite recipe sources and evidence belong privately in
  `hsoliwal/com.synexia`; this product contains only receiving candidates.
  This branch is **DRAFT / HOLD_UNVERIFIED** until canonical recipe execution,
  live-model, legal and multi-platform acceptance.

[Standalone module](standalone/README.md) ·
[Architecture decision](docs/ADR-0001-BROWSER-INDEPENDENCE.md) ·
[Standalone source boundary](docs/STANDALONE_INFERENCE_BOUNDARY.md)
