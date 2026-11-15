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
  using `CMAKE_PREFIX_PATH`; it is not a browser dependency. The explicit
  `dawn-binary` and `native-dawn` provisioning profiles are described below.
- Chromium's `optimization_guide/core/model_execution` provides an architectural
  reference for manifest-driven assets, usage-triggered acquisition, and
  brokering. It is **not** copied verbatim into this change and Chromium
  component-updater/ChromeML proprietary assets are not assumed distributable.

Every donor has its own upstream license/NOTICE/ABI obligations. Do not claim
first-party authorship of donor code.

## Maven profiles

The existing `mvn -f standalone/pom.xml verify` runs Java and fixed browser-script
contract tests with JDK 21, Maven and Node.js 22, without model or native downloads. Maven plugin dependencies must already be cached to
run it with `-o`.
The existing `mvn -f standalone/pom.xml verify` runs Java contracts without
provisioning native or model inputs. Maven plugin dependencies still follow ordinary
Maven resolution and must already be cached to run with `-o`.
The default `mvn -f standalone/pom.xml verify` remains Java-only and performs
no model acquisition or native compilation.
Maven plugin dependencies must already be cached to run with `-o`.
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
`chromellm.fork.dawnDir` (exact Dawn CMake package directory, default empty),
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
Select an installed JNI/CLI binary or build it with `fork-native`. Select the
model by local path or an explicit pinned URL; Maven can acquire the latter.
Model acquisition starts on the first inference through the existing lazy provider,
with the bounded acquisition and shutdown behavior in
[`LAZY_MODEL_CONTRACT.md`](LAZY_MODEL_CONTRACT.md). Reading the recipe and selecting
the provider do not fetch weights or initialize a native model.
The CLI executable is supplied explicitly. JNI can use an explicit `jniLib` or
the matching packaged native JAR. `modelUrl` selects a checksum-pinned HTTPS
artifact. An explicit GitHub release-download URL may follow up to three
HTTPS redirects between approved GitHub release endpoints by default. Other
HTTPS redirects require `modelFollowRedirects=true` and are bounded to five hops.
`modelDownloadTimeoutSeconds` bounds the entire acquisition (1–3600), and
`modelMaxBytes` bounds both downloaded and cached bytes (at most 64 GiB).
The default acquisition deadline is one hour and the default byte limit is 64 GiB. `modelCacheDir` resolves relative to the standalone project and defaults
to `target/model-cache` for URL recipes. Missing directories are created only
inside Maven target; externally selected caches must already exist. No
Chrome-managed model component is acquired.

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
`-Dchromellm.fork.webgpu=ON -Dchromellm.fork.prefix=/approved/dawn/install`
and, where needed, `-Dchromellm.fork.dawnDir=/approved/dawn/install/lib64/cmake/Dawn`.
If Dawn, compiler, or native dependencies are missing, CMake fails; there is
no automatic browser, GPU or provider substitution.

To acquire and execute the Linux x86_64 Dawn binary with a pinned fork build,
select `-Pfork-native,dawn-binary`. The `dawn-binary` profile enables WebGPU,
supplies the exact Dawn CMake directory and runs a Vulkan/WGSL compute test.
The existing `native-dawn` installer supports `native-fork`, the commit-validated
`fork-native` profile, and the new `native` artifact build. It runs before native
configuration and supplies its verified prefix, as described in
[NATIVE_MAVEN_BUILD.md](NATIVE_MAVEN_BUILD.md). Choose one provisioning profile;
validation rejects simultaneous Dawn installers before either runs.
To use Maven's pinned Dawn binary installer, select `-Pfork-native,native-dawn`
with the same source and commit properties. The `native-dawn` profile enables
WebGPU for the pinned source build and supplies the verified Dawn CMake prefix.
Its platform support, archive hashes and cache-integrity contract are described
in [NATIVE_MAVEN_BUILD.md](NATIVE_MAVEN_BUILD.md). The existing
`-Pnative-fork,native-dawn` entry point remains available as well.

Separate staging remains available with
`mvn -B -ntp -f standalone/pom.xml -Pnative-dawn generate-sources`, followed by
`fork-native` with `-Dchromellm.fork.webgpu=ON` and `-Dchromellm.fork.prefix`
pointing to the absolute `standalone/target/native-deps/dawn` directory.

Run the model **from the Maven build**, not from Chrome:

```sh
mvn -B -ntp -f standalone/pom.xml -Psmart-build verify \
  -Dchromellm.smart.recipe=examples/smart-build.example.properties
  -Dchromellm.smart.recipe=examples/smart-build.properties.example
```

Edit that path-based recipe first to select your JNI library and compatible GGUF
model. The separate `examples/smart-build-packaged-smoke.properties` demonstrates an
immutable test model URL and digest, packaged JNI discovery, and a managed cache. When explicitly requested, both profiles can be selected:
`-Pfork-native,smart-build`. The smart-build recipe is a *candidate generator*,
not an automatic source patcher or a guaranteed deterministic model runner.

## Maven model acquisition and offline reuse

The `real-model-test` profile downloads and tests the published, pinned model during
integration-test. It creates the configured cache directory and verifies every hit.
The `smart-build` profile accepts the same model URL/SHA/cache triple; Maven `-o`
requires a verified cached artifact before initializing the selected provider.
See [MAVEN_MODEL_TEST.md](MAVEN_MODEL_TEST.md) for the combined command and pins.
For a combined pinned-source build and tested Dawn binary, use
`-Pfork-native,dawn-binary` with the same source and commit properties. The
`dawn-binary` profile supplies its verified Dawn SDK to CMake and runs an actual
Vulkan/WGSL compute and readback test.

The separate `native-dawn` installer supports both `native-fork` and
commit-validated `fork-native`. Select `-Pfork-native,native-dawn` to provision
Dawn and pass its verified prefix into the pinned source build. The profile
enables both WebGPU properties; an explicit OFF override, synthetic mode, or
simultaneous `dawn-binary` selection is refused before native writes.

## Download and test the published model

For a small CPU smoke test, choose a writable cache directory and put this
recipe in `standalone/examples/smart-build-release.properties`. Set `jniLib`
to the library built for your platform and `modelCacheDir` to the chosen
directory. Smart Build creates it when online and refuses a missing cache offline:

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
model loading or candidate publication. The bounded HTTPS redirect and offline
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
`-Xcheck:jni`. The independent `native-binaries` job exercises the downloaded
Dawn compute binary and the `real-model` Maven profile described below.
Physical GPU execution requires its own qualification.

## Packaged runtime and binary download

The additive `native` profile builds and attaches platform/backend Maven artifacts.
`smart-native-download` resolves an already-published matching native classifier
through the configured Maven repositories, without a source build. Select it with
`smart-build` to download the native binary and run the explicit model recipe:

```sh
mvn -B -ntp -f standalone/pom.xml -Psmart-native-download,smart-build \
  -Dchromellm.smart.recipe=examples/smart-build-packaged-smoke.properties verify
```

A package must first be installed or published to a configured repository. To use
a GitHub Actions download, select `-Psmart-build` and pass
`-Dchromellm.smart.nativeArtifact=/absolute/path/to/native-classifier.jar`.
To build locally and immediately infer, select `-Pnative,smart-build` instead.
See [the Maven artifact workflow](README.md#build-and-consume-native-maven-artifacts)
for source pins, dependency notices, classifiers, and the clean CI consumer.
The `native-synthetic` profile cannot be combined with either `native-fork`
or `fork-native`. Choose one native build mode per Maven invocation.
The optional `native-dawn` profile also refuses synthetic mode. Add `smart-build`
to the selected native profiles when you want the configured inference recipe
to run after the native build; choose the compute backend explicitly in that recipe.

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

## Maven downloads and tests binary inputs

The explicit `dawn-binary` and `real-model` profiles use the same pinned binary
inputs as the native CI. Java streams each missing artifact into a temporary
file, uses validated HTTPS redirects, verifies SHA-256 and length, then
publishes it in a content-addressed cache. Every cache hit is verified again.
file, follows at most three HTTPS redirects within GitHub release storage,
verifies SHA-256 and length, then publishes it in a content-addressed cache.
Every cache hit is verified again. The origin-dependent redirect policy is
specified in [LAZY_MODEL_CONTRACT.md](LAZY_MODEL_CONTRACT.md).
file, follows at most five HTTPS redirects, verifies SHA-256 and length, then
publishes it in a content-addressed cache. Every cache hit is verified again.
Maven `-o` permits verified cache reuse and refuses a missing binary.

| Profile | Work performed |
| --- | --- |
| `dawn-binary` | Download the native fork CI's Linux x86_64 Dawn v2.0.0 binary; verify both ZIP and nested TAR; extract a fresh installation; build and run an actual Vulkan/WGSL compute and readback test. |
| `real-model` | Download the pinned Apache-2.0 SmolLM2 model; run the real JNI provider, selected-device and response-identity smoke test with `-Xcheck:jni`. |
| `smart-build` | Resolve an explicitly configured `modelUrl` lazily when inference is requested. Relative recipe paths resolve against `standalone/`; missing caches under `target/` are created. External caches must already exist. |
| `smart-build` | Resolve an explicitly configured `modelUrl` lazily when inference is requested. Relative recipe paths resolve against `standalone/`; missing cache directories are created when online. |

The default cache for these two binary profiles is `${user.home}/.cache/chromellm`;
set `-Dchromellm.artifact.cache=/path/to/cache` to change it. The smart-build recipe
continues to specify its own `modelCacheDir`. No native or model download occurs
in the default Java-only build. Ordinary JDK HTTPS proxy and trust-store settings
apply to the Java downloader; configure them in `MAVEN_OPTS` when required.

Download and execute the Dawn package on Linux x86_64 with a Vulkan driver:

```sh
mvn -f standalone/pom.xml -Pdawn-binary verify
```

For a hosted CPU machine, select Mesa Lavapipe through `VK_ICD_FILENAMES`.
The test requests Vulkan and verifies four computed words; it fails when no
matching adapter is available. Software Vulkan results are not discrete-GPU
performance measurements. The automatic binary profile is Linux x86_64 only;
other platforms retain the existing native source and installed-library profiles.

Compile your pinned native fork, download the real model, and test JNI:

```sh
mvn -f standalone/pom.xml -Pfork-native,real-model verify \
  -Dchromellm.fork.source=/absolute/path/to/synexia-llmcpp \
  -Dchromellm.fork.commit=FULL_40_HEX_COMMIT
```

Add `dawn-binary` to the profile list to build that fork with the downloaded
Dawn provider and require WebGPU inference. Maven passes the package's explicit
`lib64/cmake/Dawn` directory to CMake, including on hosts that do not search
`lib64` through `CMAKE_PREFIX_PATH`. A compatible native source checkout, Java 21,
CMake and a C++ compiler are required; the model binary is separate from the JNI
runtime. The `fork-native` runtime is built from the selected source. The separate
`native` profile builds consumable native JARs; `smart-native-download` resolves
a classifier already installed or published to the configured Maven repository.

To request smart-build advice with a lazily downloaded model on Linux:

```sh
mvn -f standalone/pom.xml -Pfork-native,smart-build verify \
  -Dchromellm.fork.source=/absolute/path/to/synexia-llmcpp \
  -Dchromellm.fork.commit=FULL_40_HEX_COMMIT \
  -Dchromellm.smart.recipe=examples/smart-build-download.properties
```

The sample selects CPU inference. For WebGPU, use the downloaded or installed
Dawn provider and set `computeBackend=webgpu` with a positive `gpuLayers` in your
recipe. The smart build validates its output directory before opening a model.
Maven's compiler and test exit codes remain authoritative; generated advice is
written only to the existing `target/smart-build` output.

### Binary identities and upstream sources

- Model: [ChromeLLM release](https://github.com/hsoliwal/ChromeLLM/releases/tag/model-smollm2-135m-q4km-8030f045),
  `weights.bin`, 105,454,144 bytes,
  SHA-256 `8030f04528538d47bda434f6f0bdf3952c40a58123e4d5e755332f23731a8684`.
  The release retains its Apache-2.0 LICENSE and NOTICE. This is the same GGUF
  used by the historical real-inference workflow.
- Dawn: [native WebGPU CI binaries v2.0.0](https://github.com/reeselevine/dawn/releases/tag/v2.0.0),
  revision `5e9a4865b1635796ccc77dd30057f2b4002a1355`, Linux ZIP, 9,978,094 bytes,
  SHA-256 `a5a87d9513e1fc109c355628baebe6b85fe7aa258f8ed2d2cf71ddc902e778bb`.
  Its nested TAR is independently verified before extraction. Package copyright
  and license files remain in the installed tree.
- The `native-binaries` job in `standalone-core.yml` executes the downloaded Dawn
  compute test and a real-model JNI test. The public CI source is the exact
  `ggml-org/llama.cpp` revision `889f2ec96deacbc2d8a9a67dbe181eaa43a2fb3b`
  already used by the repository's historical real-model workflow. This public
  source lane does not require access to private native forks; local Maven use
  can select the independently pinned fork checkout.

## Dawn provisioning profiles

Choose one Dawn provisioning profile per Maven invocation. Maven rejects
`native-dawn,dawn-binary` during validation, before either installer runs.

| Profile | Supported downloaded binary | Build entry point | Verification provided |
| --- | --- | --- | --- |
| `native-dawn` | Linux x86_64 and macOS arm64 | `-Pnative-dawn generate-sources`; combine with `native-fork`, `fork-native` or `native` for its source build | SHA-256-pinned ZIP, sealed installed tree and CMake package discovery under `target/native-deps/dawn` |
| `native-dawn` | Linux x86_64 and macOS arm64 | `-Pnative-dawn generate-sources`; combine with `native-fork` or `fork-native` for its source build | SHA-256-pinned ZIP, sealed installed tree and CMake package discovery under `target/native-deps/dawn` |
| `native-dawn` | Linux x86_64 and macOS arm64 | `-Pnative-dawn generate-sources`; combine with `native-fork` for its source build | SHA-256-pinned ZIP, sealed installed tree and CMake package discovery under `target/native-deps/dawn` |
| `dawn-binary` | Linux x86_64 | `-Pdawn-binary verify`; combine with `fork-native` for its pinned source build | Exact-size/SHA-256 ZIP and inner archive, plus actual Vulkan WGSL compute/readback under `target/dawn-sdk` |

The `native-dawn` source-file installer and its Linux/macOS workflow remain
available. Its `native-fork` integration uses `chromellm.native.source` and
`chromellm.native.dawn.prefix`. The `fork-native,dawn-binary` path uses
`chromellm.fork.source`, a full `chromellm.fork.commit`, and the independently
verified runtime tests documented above. Maven rejects combinations that select
multiple native source builds or simultaneous Dawn installers. The standalone
`dawn-binary` verifier pairs with `fork-native`; `native-dawn` can precede any real
native source profile. Each supported pairing provisions Dawn before CMake.

Maven's offline setting is enforced by `dawn-binary`, `real-model` and
`smart-build`. The `native-dawn` source-file installer controls acquisition
`chromellm.native.dawn.prefix`; its `fork-native` integration uses
`chromellm.fork.source` and a full `chromellm.fork.commit`, forwarding the same
verified Dawn prefix. Both WebGPU settings must remain `ON` with `native-dawn`.
The `fork-native,dawn-binary` path uses the pinned source properties and the
independently verified runtime tests documented above. Maven rejects combinations
that select multiple native source builds, or pair `dawn-binary` with `native-fork`;
each supported pairing provisions Dawn before its CMake configuration.

Maven's offline setting is enforced by `dawn-binary`, `real-model`,
`real-model-test` and `smart-build`. The `native-dawn` source-file installer controls acquisition
verified runtime tests documented above. Maven rejects multiple native source
builds, both Dawn installers together, and either installer paired with the other
source profile; each supported pairing provisions Dawn before its CMake configuration.

Maven's offline setting is enforced by `dawn-binary`, `real-model`, `real-model-test` and
`smart-build`. The `native-dawn` source-file installer controls acquisition
independently of Maven's offline setting, so use an already verified installer
cache when running that profile without network access.
