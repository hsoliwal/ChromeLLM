# Fork-native Maven smart build contract

ChromeLLM is a standalone Java 21/JNI product assembled from qualified **source forks**.
The long-term donor map follows Chromium's published
`Client -> Broker -> AssetProvider -> Service` on-device-model architecture.
This first executable Maven slice builds the actual fork-native
`hsoliwal/synexia-llmcpp` JNI bridge and optionally invokes its model runtime.
It does not call Chrome's browser APIs, start Chromium, or silently replace
a model runtime with a hosted provider.

## Forks and boundaries

- `hsoliwal/ChromeLLM`: Java 21 API, JNI bridge, fixed build lifecycle.
- `hsoliwal/synexia-llmcpp`: the independently checked-out llama.cpp/GGML native
  source to compile via ChromeLLM's existing CMake integration; the checkout must
  be at the **full operator-supplied 40-hex commit** and have no modified or untracked files.
- `hsoliwal/dawn`: optional, separately built WebGPU dependency found by CMake
  using `CMAKE_PREFIX_PATH`. The existing `native-dawn` profile can instead
  acquire a platform-specific, checksum-pinned Dawn release package.
- Chromium's `optimization_guide/core/model_execution` provides an architectural
  reference for manifest-driven assets, usage-triggered acquisition, and
  brokering. It is **not** copied verbatim into this change and Chromium
  component-updater/ChromeML proprietary assets are not assumed distributable.

Every donor has its own upstream license/NOTICE/ABI obligations. Do not claim
first-party authorship of donor code.

## Maven profiles

The existing `mvn -f standalone/pom.xml verify` runs Java contract tests without
model or native downloads. Maven plugin dependencies must already be cached to
run it with `-o`.

`-Pfork-native` verifies a pinned *local source fork* before invoking CMake.
Required properties:
`chromellm.fork.source`, `chromellm.fork.commit`. Optional:
`chromellm.fork.webgpu` (default `OFF`),
`chromellm.fork.prefix` (Dawn install prefix, default empty),
`chromellm.fork.configuration` (default `Release`).
Output stays in `standalone/target/fork-native`. CMake neither checks out
nor downloads donor source, and changing a tracked file or HEAD causes failure
before native configuration. Dirty or untracked donor files are never
silently removed or staged: the build fails closed.

`-Psmart-build` invokes the compiled Java inference API during Maven's
`verify` phase only when `chromellm.smart.recipe` identifies an existing
UTF-8 `.properties` recipe inside the standalone project. A profile must be
selected explicitly. The recipe is not read in ordinary Maven builds.

Recipe keys:

```properties
promptFile=src/test/resources/build-prompt.txt
backend=jni
jniLib=/absolute/path/to/libchromellm_llama_jni.so
model=/absolute/path/to/licensed.gguf
maxTokens=256
timeoutSeconds=120
```

Alternatively use `backend=cli` and `llamaBin`; or use `modelUrl`,
`modelSha256`, and `modelCacheDir` **instead** of `model`. The JDK
backend parser validates each combination and does not silently fall back.
The operator selects the JNI/CLI binary and either a local model or a pinned
model URL. The existing lazy resolver downloads a missing URL-selected model.
`modelUrl` is an explicit, checksum-pinned HTTPS artifact, not a Chrome-managed
proprietary model component.

## Invoke from Maven (no browser)

Build the pinned **source fork** with the existing JNI CMake integration:

```sh
mvn -B -ntp -f standalone/pom.xml -Pfork-native verify \
  -Dchromellm.fork.source=/approved/src/synexia-llmcpp \
  -Dchromellm.fork.commit=650ac3739d993e1d425f2d5db3c4205b3606cc30 \
  -Dchromellm.fork.webgpu=OFF
```

The commit above is an observed historical fork revision, not an assertion that
the operator's current checkout is at that revision. Choose and pin the
checked-out commit. To enable the separately built Dawn fork, use
`-Dchromellm.fork.webgpu=ON -Dchromellm.fork.prefix=/approved/dawn/install`.
If Dawn, compiler, or native dependencies are missing, CMake fails; there is
no automatic browser, GPU or provider substitution.

To use Maven's pinned Dawn binary installer, select `-Pfork-native,native-dawn`
with the same source and commit properties. The `native-dawn` profile enables
WebGPU for the pinned source build and supplies the verified Dawn CMake prefix.
Its platform support, archive hashes and cache-integrity contract are described
in [NATIVE_MAVEN_BUILD.md](NATIVE_MAVEN_BUILD.md). The existing
`-Pnative-fork,native-dawn` entry point remains available as well.

Run the model **from the Maven build**, not from Chrome:

```sh
mvn -B -ntp -f standalone/pom.xml -Psmart-build verify \
  -Dchromellm.smart.recipe=examples/smart-build.properties.example
```

Edit the provided recipe first to select your JNI library and licensed GGUF
model. When explicitly requested, both profiles can be selected:
`-Pfork-native,smart-build`. The smart-build recipe is a *candidate generator*,
not an automatic source patcher or a guaranteed deterministic model runner.

## Download and test the published model

For a small CPU smoke test, create a writable cache directory and put this
recipe in `standalone/examples/smart-build-release.properties`. Set `jniLib`
to the library built for your platform and `modelCacheDir` to that existing
directory:

```properties
promptFile=examples/smart-build-prompt.txt
backend=jni
jniLib=/absolute/path/to/libchromellm_llama_jni.so
modelUrl=https://github.com/hsoliwal/ChromeLLM/releases/download/model-smollm2-135m-q4km-8030f045/weights.bin
modelSha256=8030f04528538d47bda434f6f0bdf3952c40a58123e4d5e755332f23731a8684
modelCacheDir=/absolute/path/to/model-cache
computeBackend=cpu
gpuLayers=0
contextTokens=512
threads=2
maxTokens=48
timeoutSeconds=90
```

Run `mvn -B -ntp -f standalone/pom.xml -Psmart-build verify
-Dchromellm.smart.recipe=examples/smart-build-release.properties` on one line.
The first inference downloads and verifies the model; later runs verify and
reuse the cache. A corrupt cache entry fails SHA-256 validation before native
model loading or candidate publication. The bounded GitHub release redirect
rules are documented in [LAZY_MODEL_CONTRACT.md](LAZY_MODEL_CONTRACT.md).

The published `weights.bin` is the unchanged 105,454,144-byte SmolLM2-135M-Instruct
Q4_K_M GGUF v3 model, despite the filename extension. Its
[Apache-2.0 license](https://github.com/hsoliwal/ChromeLLM/releases/download/model-smollm2-135m-q4km-8030f045/LICENSE)
and [source notice](https://github.com/hsoliwal/ChromeLLM/releases/download/model-smollm2-135m-q4km-8030f045/NOTICE.txt)
accompany the release. This small model is an execution fixture, with no claim
about generated-code quality.

The `real-cpu-smart-build` GitHub job starts with an empty model cache, builds
the current JNI bridge from an exact public llama.cpp revision, and downloads
the release through Maven's production Java resolver. It then checks actual
inference, verified cache reuse with an unreachable URL, same-size corruption
refusal, output receipts, deterministic CPU repetition and native close under
`-Xcheck:jni`. Dawn/WebGPU runtime execution requires a separate GPU gate.

The `native-synthetic` profile cannot be combined with either `native-fork`
or `fork-native`. Choose one native build mode per Maven invocation.
The optional `native-dawn` profile also refuses synthetic mode. Add `smart-build`
to the selected native profiles when you want the configured inference recipe
to run after the native build; choose the compute backend explicitly in that recipe.

## Artifact and safety contract

The smart recipe reads a bounded UTF-8 prompt inside the standalone project.
The inferred candidate is written **only under** `standalone/target/smart-build/`
using an exclusive, atomically published artifact name keyed by recipe and
prompt SHA-256. A separate receipt records recipe SHA-256, prompt SHA-256,
candidate SHA-256, and backend identity; no prompt content or credentials are
logged to the Maven console.

On replay, identical artifacts are retained; a divergent model response for
the same recipe and prompt hard-fails instead of silently replacing a prior
candidate. Inference is not assumed deterministic: sampling/driver/provider
differences require review and a new identified run, not an equivalence claim.
This stage **never applies generated source changes**. Promotion requires
separate explicit approval, patch review, lint, compilation, tests, and runtime
verification.

This is a source-fork Maven builder, not a Maven wrapper around a live browser.
Chromium broker/asset-provider behavioral parity, Google proprietary model
compatibility, and CPU/GPU performance require separate source-level
qualification. A passing JNI synthetic test is not proof of real model execution.
