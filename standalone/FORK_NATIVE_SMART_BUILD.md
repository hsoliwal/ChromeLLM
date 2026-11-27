# Native Maven Smart Build

ChromeLLM can build an explicitly pinned native source checkout, or download a
compatible JNI runtime and model when inference is first requested. It also retains
the platform native JAR build, Maven download, and packaged-runtime loader. Maven uses
the existing Java 21/JNI provider for these routes. The generated advice remains a
reviewable artifact; the original Maven compiler and test results stay authoritative.
The reusable [build-advice action](BUILD_AI_ACTION.md) provides separate pre-build
and post-build phases around a repository's existing build command, with explicit
provider selection, verified JAR input and bounded diagnostic packets.

## Choose the inputs

| Input | Existing local input | Lazy downloaded input |
| --- | --- | --- |
| JNI runtime | `jniLib` in the Smart Build properties | `jniUrl`, `jniSha256`, `jniBytes`, `jniCacheDir` |
| GGUF model | `model` | `modelUrl`, `modelSha256`, optional `modelBytes`, `modelCacheDir` |
| CLI provider | `backend=cli` and `llamaBin` | Model download is supported; CLI binary download is not provided |

Each downloaded artifact has an independent identity. Use a raw `.so`, `.dll`, or
`.dylib` from the platform's tested runtime bundle for `jniUrl`. The lazy loader does
not unpack archives or find missing native dependencies. Model and native bytes are
verified before the provider opens. Local paths and downloaded artifacts can be
combined, but each input must have exactly one selection.

If neither `jniLib` nor the native URL quartet is selected, JNI discovers the
matching installed native classifier JAR through `NativeLibraryLoader`. That loader
verifies the package manifest and dependency closure before extracting and loading
its native files. It performs no network download. The `smart-native-download`
profile supplies that JAR through Maven's resolver; the raw `jniUrl` quartet supplies
one independently pinned self-contained library through the lazy artifact owner.

Constructing a URL-backed provider does not create caches, download files, or initialize a
model. First inference acquires the configured inputs, and later calls reuse the
loaded provider. A new process verifies cached bytes again. Maven `-o` permits only
verified cache hits and refuses missing or corrupt inputs without network access.
See [the acquisition and lifetime contract](LAZY_MODEL_CONTRACT.md) for deadlines,
redirect checks, cancellation, concurrency and cleanup.

## Download both runtime and model from Maven

Edit `standalone/examples/smart-build-native-download.properties`. Fill in the
JNI URL, SHA-256 and exact byte count from a tested native release's
`runtime-manifest.properties`. Select the asset for your operating system and CPU
architecture. The sample model identity is already pinned to the published small
CPU smoke-test model.

```properties
promptFile=examples/smart-build-prompt.txt
backend=jni
jniUrl=HTTPS_URL_OF_PLATFORM_JNI_LIBRARY
jniSha256=SHA256_FROM_RUNTIME_MANIFEST
jniBytes=EXACT_BYTES_FROM_RUNTIME_MANIFEST
jniCacheDir=target/native-cache
modelUrl=https://github.com/hsoliwal/ChromeLLM/releases/download/model-smollm2-135m-q4km-8030f045/weights.bin
modelSha256=8030f04528538d47bda434f6f0bdf3952c40a58123e4d5e755332f23731a8684
modelBytes=105454144
modelCacheDir=target/model-cache
computeBackend=cpu
gpuLayers=0
contextTokens=512
threads=2
maxTokens=32
timeoutSeconds=120
```

Run from the repository root:

```sh
mvn -B -ntp -f standalone/pom.xml -Psmart-build verify \
  -Dchromellm.smart.recipe=examples/smart-build-native-download.properties

mvn -o -B -ntp -f standalone/pom.xml -Psmart-build verify \
  -Dchromellm.smart.recipe=examples/smart-build-native-download.properties
```

The first run downloads missing inputs. The second reuses reverified caches in a
fresh Maven process. Maven's own plugins and Java dependencies must also be cached
before `-o` can run. Maven offline mode overrides `offline=false` in the properties.
The Maven profile passes `${settings.offline}` as the explicit fourth runtime
argument; legacy three-argument callers use the `chromellm.offline` system property.
Relative prompt and cache paths resolve from `standalone/`; online acquisition
creates a missing configured cache directory. Configuration errors are rejected
before any download. Existing corrupt cache entries are refused rather than replaced.

The native release workflow is an opt-in publishing operation. A source change or a
local package build alone does not create a downloadable release. Until a tested
runtime release is published, use a reviewed HTTPS host for the packaged library or
select the locally built library with `jniLib`.

Legacy model recipes without the three policy fields retain checked generic HTTPS
redirects with a five-hop bound. Supplying `modelFollowRedirects`,
`modelDownloadTimeoutSeconds`, or `modelMaxBytes` selects explicit policy, where
`modelFollowRedirects=false` is the default. Under that policy, GitHub release
download URLs may follow up to three redirects within approved GitHub release
endpoints; other HTTPS redirects require `modelFollowRedirects=true`.
`modelDownloadTimeoutSeconds` bounds the entire acquisition
(1–3600, default 3600), and `modelMaxBytes` bounds downloaded and cached model bytes
(at most 64 GiB). `modelBytes`, when supplied, also pins the exact length.
For URL recipes, `modelCacheDir` defaults to `target/model-cache`. Maven prepares
caches beneath its verified target after provider validation and just before
inference. Recipes using an existing CLI or local/packaged JNI runtime prepare
explicit external model caches after option validation and symlink-ancestor checks,
before opening the provider. Recipes using a raw JNI URL retain their external model
and native cache directories under the lazy acquisition owner. Offline execution
creates no cache directories.

## Build or download packaged native Maven artifacts

The `native` profile builds and attaches platform/backend native classifier JARs.
The separate `smart-native-download` profile resolves an already-published matching
classifier through the configured Maven repositories, without a native source build:

```sh
mvn -B -ntp -f standalone/pom.xml -Psmart-native-download,smart-build \
  -Dchromellm.smart.recipe=examples/smart-build-packaged-smoke.properties verify
```

The package must first be installed or published to a configured repository.
`chromellm.smart.nativeVersion` selects its version; the default is the current
project version. Maven copies the resolved JAR to
`target/smart-runtime/chromellm-native.jar`, and Smart Build includes the selected
`${chromellm.smart.nativeArtifact}` on its runtime classpath. Maven's repository
offline rules apply to this resolution, independently of lazy artifact acquisition.

An existing classifier JAR, including one downloaded from GitHub Actions, can be
selected explicitly:

```sh
mvn -B -ntp -f standalone/pom.xml -Psmart-build \
  -Dchromellm.smart.nativeArtifact=/absolute/path/to/chromellm-native.jar \
  -Dchromellm.smart.recipe=examples/smart-build-packaged-smoke.properties verify
```

Use `-Pnative,smart-build` to build and consume the matching package locally.
The package resource manifest, native installer, dependency notices, classifiers,
CTest packaging fixtures and clean consumer remain described in
[the native Maven artifact workflow](README.md#build-and-consume-native-maven-artifacts).
The packaged loader uses `${user.home}/.cache/chromellm/native` unless
`chromellm.native.cache` selects another extraction cache. Raw URL caches remain
explicit `jniCacheDir` and `modelCacheDir` selections.

## Build a pinned native source checkout

The `fork-native` profile validates an independently checked-out source repository
before CMake runs. Provide its absolute path and exact full 40-hex commit:

```sh
mvn -B -ntp -f standalone/pom.xml -Pfork-native verify \
  -Dchromellm.fork.source=/approved/src/llama.cpp \
  -Dchromellm.fork.commit=FULL_40_HEX_COMMIT \
  -Dchromellm.fork.webgpu=OFF
```

The source must have no modified or untracked files. CMake does not download,
change, or clean the donor checkout. Public CI uses `ggml-org/llama.cpp` commit
`889f2ec96deacbc2d8a9a67dbe181eaa43a2fb3b`. An independently pinned
`hsoliwal/synexia-llmcpp` checkout remains supported; private source access is an
explicit operator input.

Output stays under `standalone/target/fork-native`. Optional source-build properties
are `chromellm.fork.webgpu` (default `OFF`), `chromellm.fork.prefix`,
`chromellm.fork.dawnDir`, and `chromellm.fork.configuration` (default `Release`).
For an installed Dawn package, use `chromellm.fork.webgpu=ON` and its CMake prefix or
exact `DawnConfig.cmake` directory. Missing dependencies fail the build.

To build a CPU runtime suitable for lazy acquisition, additionally set
`-Dchromellm.fork.distributableCpu=ON`, then stage it:

```sh
cmake --build standalone/target/fork-native --config Release \
  --target chromellm_runtime_package --parallel 2
```

The package contains one JNI library with llama/GGML statically linked, exact digest
and length, dependency audit, source identities, and required license/notice files.
It disables host-specific instruction selection and dynamic GGML providers. This
option excludes synthetic and WebGPU builds. GNU/Linux packages also include the
GCC runtime license and exception texts. Check `glibc.minimum` in
`runtime.properties`; a library built on a newer Linux system may require newer
system libraries than a CI release. Windows and macOS have their own dependency
checks. Cross-platform compatibility is established by each platform's runtime job.

## Maven profile combinations

The ordinary `mvn -f standalone/pom.xml verify` runs Java contracts using JDK 21
and Maven, without native or model provisioning. It does not require Node.js or
a browser installation.
Dependency resolution follows Maven's normal online/offline policy.
Its Java contract suite also verifies the fork-source pin and inference recipe;
no native library or model is required by those offline test fixtures.

| Profile | Work performed |
| --- | --- |
| `native-synthetic` | Build the test-only JNI fixture and run CTest; never a distributable runtime |
| `native-fork` | Build a local source checkout through the existing native entry point |
| `fork-native` | Require a clean exact source commit, then build its native runtime |
| `native` | Build and attach the selected platform/backend native classifier JAR |
| `native-dist` | Attach a native runtime ZIP from a real `native-fork` or `fork-native` build, with an explicit classifier and reviewed notices |
| `smart-native-download` | Resolve an installed or published native classifier JAR through Maven repositories |
| `smart-build` | Run an explicitly selected inference properties file during `verify` |
| `real-model` | Acquire the pinned model and run the selected JNI real-model smoke test |
| `real-model-test` | Run the real GGUF contract suite with explicit native/model pins and Maven offline policy |
| `dawn-binary` | Download and verify the Linux x86_64 Dawn archive and its nested TAR; run Vulkan/WGSL compute and readback |
| `native-dawn` | Retain the Linux x86_64/macOS arm64 Dawn installer and sealed installation |

Choose only one native build profile. `smart-build` can accompany that profile when
the properties select its local JNI library or packaged artifact. Choose only one Dawn
installer. Use `fork-native,dawn-binary` for the pinned source build with the binary
Dawn provider. `native-dawn` supports `native-fork`, `fork-native`, and the `native`
artifact build. It enables the corresponding WebGPU settings and passes the
verified Dawn prefix before CMake configuration. Synthetic mode, conflicting Dawn
installers, and explicit contradictory WebGPU settings are refused before native
writes. To use an already installed Dawn package with `fork-native`, pass its prefix
explicitly. The separate `native-dist` ZIP retains its explicit classifier and
reviewed-notices requirements and refuses synthetic or prebuilt-library mode. See
[NATIVE_MAVEN_BUILD.md](NATIVE_MAVEN_BUILD.md) for the original installer contract.

The additional `real-model-test` contract suite and its `chromellm.real.*` properties
are documented in [MAVEN_MODEL_TEST.md](MAVEN_MODEL_TEST.md).

For CPU source build plus model download and inference:

```sh
mvn -B -ntp -f standalone/pom.xml -Pfork-native,real-model verify \
  -Dchromellm.fork.source=/approved/src/llama.cpp \
  -Dchromellm.fork.commit=FULL_40_HEX_COMMIT
```

For Dawn's downloaded compute test on Linux x86_64:

```sh
mvn -B -ntp -f standalone/pom.xml -Pdawn-binary verify
```

A Vulkan driver is required. Mesa Lavapipe can be selected with
`VK_ICD_FILENAMES` for a software-driver test. Software Vulkan results are not
physical-GPU performance measurements. `dawn-binary`, `real-model`, `real-model-test`
and `smart-build` honor Maven `-o`. The retained `native-dawn` source-file installer has its own cache
policy. Ordinary JDK proxy/trust-store settings can be supplied in `MAVEN_OPTS`.

## GitHub runtime qualification and releases

The `native-runtime` matrix builds Linux x86_64, Windows x86_64 and macOS arm64
CPU packages from an exact native source commit. Each job checks dependency closure,
serves the new JNI library through a local HTTPS endpoint, downloads the published
model, and executes the production Maven Smart Build path. It then stops the native
server and runs a fresh Maven `-o` invocation against the caches. Missing native
cache and same-size native corruption must fail; model integrity, backend receipts,
repeat inference and close are checked by the existing real-model contracts.

The matrix uploads runtime bundles and test logs as Actions artifacts. The separate
`native-runtime-consumer` matrix then downloads each platform bundle from that same
workflow run. It verifies the workflow/source commit, platform, native source
identities, and both JNI and Java SHA-256 and byte counts before execution. Each
consumer creates an empty Maven repository and a source-free receiving project,
installs the exact downloaded Java JAR, and calls its existing `SmartBuildMain`
through Maven. JVM class-load records prove that runtime classes came from that
installed JAR. The consumer has no production source compilation or `target/classes`
fallback. The established real-model test alone is compiled against the downloaded
JAR for its final lifecycle checks.

The consumer repeats cold HTTPS native/model acquisition, warm `mvn -o` inference,
missing-native and same-size native/model corruption refusal, receipt checks and
native close. Its Maven dependency repository is separate from the verified native
and model caches. Bootstrap resolves the normal Maven execution/install plugins;
the warm run uses those cached dependencies with Maven offline mode. Logs retain
commands, artifact identities and bounded class-origin traces without TLS keys.

A manual run with `publish_runtime=true` publishes a commit-named prerelease only
after the Java, synthetic JNI, existing real-model and CPU producer gates, and all
three downloaded-artifact consumer jobs pass. It includes raw native assets for
`jniUrl`, complete ZIP bundles with notices and platform requirements, the tested
Java JAR, and `runtime-manifest.properties`. Native source repository/commit can be
selected explicitly; private sources require the configured `NATIVE_SOURCE_TOKEN`.
The separate native Dawn tests retain their own jobs. The installed-Chrome provider
and its browser tests have been removed.
CPU success makes no claim about WebGPU execution or generated-code quality.


### Try an uploaded bundle locally

Use a successful `Standalone inference` run and its exact checked-out commit.
The checkout must retain Git's exact source bytes, including line endings. For a
fresh checkout, including on Windows:

```sh
git -c core.autocrlf=false -c core.eol=lf clone https://github.com/hsoliwal/ChromeLLM.git
cd ChromeLLM
git config core.autocrlf false
git config core.eol lf
git fetch origin FULL_40_HEX_WORKFLOW_COMMIT
git checkout --detach FETCH_HEAD
```

Use the workflow's actual commit, which can be a pull-request merge commit. The
native producer and consumer jobs set the byte-preserving policy before checkout
on every runner. The verifier refuses changed source bytes.

For example, on Linux x86_64, download the matching Actions artifact with GitHub
CLI, then run the consumer from the ChromeLLM checkout:

```sh
gh run download RUN_ID --repo hsoliwal/ChromeLLM \
  --name chromellm-native-linux-x86_64 --dir downloaded-runtime
python standalone/native/tests/lazy_runtime_smoke.py \
  --mode artifact-consumer --project standalone --package downloaded-runtime \
  --expected-commit FULL_40_HEX_WORKFLOW_COMMIT --expected-platform linux-x86_64
```

Use `chromellm-native-windows-x86_64` / `windows-x86_64` on Windows, or
`chromellm-native-macos-aarch64` / `macos-aarch64` on Apple Silicon. Java/Javac/
Keytool 21, Maven, Git, OpenSSL and Python 3.12 must be available. `--mvn`, `--java`,
`--javac` and `--keytool` can select explicit tool paths. The checkout and bundle
commit must agree; the script also verifies the native owners and unchanged
real-model test against Git. Normal consumer runs require `java.bytes` in the
bundle manifest. For a historical bundle that predates that field, both
`--expected-java-bytes` and `--expected-java-sha256` are required from independently
recorded artifact identities; supplied pins must agree with the manifest and JAR.

This path consumes the ordinary standalone Java runtime and the raw native library
inside the Actions bundle. The `smart-native-download` classifier-JAR workflow
remains a separate supported Maven route. An Actions artifact may be used before a
commit-named runtime release has been published.

## Review-only outputs and source identities

Smart Build accepts a bounded UTF-8 prompt inside the standalone project. It writes
candidates only under `standalone/target/smart-build/`, using exclusive atomic
publication keyed by recipe and prompt SHA-256. Receipts identify recipe, prompt,
candidate, and actual backend. Output validation happens before model or native
acquisition. Console logs omit prompt content and credentials.

Identical replay output is retained; divergent model output for the same input
identity fails instead of replacing an existing candidate. Source changes require
the normal review and compile/test process. The public Java factory
`StandaloneMain.openBackend(String... args)` exposes the same provider to an
embedding Maven plugin, which owns and closes its returned `ModelBackend`.

The published model is SmolLM2-135M-Instruct Q4_K_M, 105,454,144 bytes, SHA-256
`8030f04528538d47bda434f6f0bdf3952c40a58123e4d5e755332f23731a8684`.
Its [release](https://github.com/hsoliwal/ChromeLLM/releases/tag/model-smollm2-135m-q4km-8030f045)
includes Apache-2.0 LICENSE and source NOTICE. It is a small execution fixture.
The existing Dawn binary pin is the
[v2.0.0 native WebGPU CI package](https://github.com/reeselevine/dawn/releases/tag/v2.0.0),
revision `5e9a4865b1635796ccc77dd30057f2b4002a1355`, Linux ZIP 9,978,094 bytes,
SHA-256 `a5a87d9513e1fc109c355628baebe6b85fe7aa258f8ed2d2cf71ddc902e778bb`.
The nested archive and installed licenses remain verified separately.

ChromeLLM, native forks, Dawn, and model assets retain their respective
license/NOTICE obligations. Chromium's published broker/asset-provider design is
an architectural reference; no proprietary Chrome model component is supplied or
assumed redistributable by this integration.
