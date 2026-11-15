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
  using `CMAKE_PREFIX_PATH`; it is not a browser dependency.
- Chromium's `optimization_guide/core/model_execution` provides an architectural
  reference for manifest-driven assets, usage-triggered acquisition, and
  brokering. It is **not** copied verbatim into this change and Chromium
  component-updater/ChromeML proprietary assets are not assumed distributable.

Every donor has its own upstream license/NOTICE/ABI obligations. Do not claim
first-party authorship of donor code.

## Maven profiles

The default `mvn -f standalone/pom.xml verify` remains Java-only and performs
no model acquisition or native compilation.
Its Java contract suite also verifies the fork-source pin and inference recipe;
no native library or model is required by those offline test fixtures.

The existing `native-fork` and `native-synthetic` profiles remain available.
Choose `fork-native` when the build must validate a complete Git commit pin before
CMake runs. Maven rejects any pair of native build profiles during `validate`,
before native configuration. Select one native build profile; `smart-build` can accompany
the selected native profile when its recipe explicitly names that profile's output.

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
Model acquisition starts on the first inference through the existing lazy provider,
with the bounded acquisition and shutdown behavior in
[`LAZY_MODEL_CONTRACT.md`](LAZY_MODEL_CONTRACT.md). Reading the recipe and selecting
the provider do not fetch weights or initialize a native model.
The selected JNI/CLI runtime is supplied or built from the pinned source. Model bytes
may be acquired through the explicit checksum-pinned URL or the `real-model-test` profile.
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

The `native-dawn` installer can accompany the commit-validated fork build in
one invocation: `-Pfork-native,native-dawn`. It stages the verified dependency,
enables WebGPU, and supplies the staged Dawn prefix to CMake. The existing
`native-fork,native-dawn` combination remains supported.

Separate staging remains available with
`mvn -B -ntp -f standalone/pom.xml -Pnative-dawn generate-sources`, followed by
`fork-native` with `-Dchromellm.fork.webgpu=ON` and `-Dchromellm.fork.prefix`
pointing to the absolute `standalone/target/native-deps/dawn` directory.
See [`NATIVE_MAVEN_BUILD.md`](NATIVE_MAVEN_BUILD.md) for dependency qualification.

Run the model **from the Maven build**, not from Chrome:

```sh
mvn -B -ntp -f standalone/pom.xml -Psmart-build verify \
  -Dchromellm.smart.recipe=examples/smart-build.example.properties
```

Edit the provided recipe first to select your JNI library and licensed GGUF
model. When explicitly requested, both profiles can be selected:
`-Pfork-native,smart-build`. The smart-build recipe is a *candidate generator*,
not an automatic source patcher or a guaranteed deterministic model runner.

## Maven model acquisition and offline reuse

The `real-model-test` profile downloads and tests the published, pinned model during
integration-test. It creates the configured cache directory and verifies every hit.
The `smart-build` profile accepts the same model URL/SHA/cache triple; Maven `-o`
requires a verified cached artifact before initializing the selected provider.
See [MAVEN_MODEL_TEST.md](MAVEN_MODEL_TEST.md) for the combined command and pins.

## Artifact and safety contract

The smart recipe reads a bounded UTF-8 prompt inside the standalone project.
The inferred candidate is written **only under** `standalone/target/smart-build/`
using an exclusive, atomically published artifact name keyed by recipe and
prompt SHA-256. A separate receipt records recipe SHA-256, prompt SHA-256,
candidate SHA-256, and backend identity; no prompt content or credentials are
logged to the Maven console.
The output location is validated before artifact acquisition or provider opening.
Existing artifact comparisons read at most the expected bounded content size plus
one byte; an oversized prior artifact is rejected without loading it into memory.

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
