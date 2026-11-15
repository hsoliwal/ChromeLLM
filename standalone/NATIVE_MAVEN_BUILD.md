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

## Lazy, verified Dawn artifacts

The upstream llama.cpp WebGPU workflow already acquires the Dawn release from
`reeselevine/dawn`, tag `v2.0.0`, rather than building Chromium or ChromeML.
ChromeLLM reuses that **native Dawn dependency**, never browser-managed model
weights. The opt-in `native-dawn` Maven profile runs the Java 21 source-file
installer in `standalone/native/DawnArtifactInstaller.java` during
`generate-sources`; `native-fork` then configures CMake during
`generate-resources`. Maven owns both operations:

```sh
mvn -B -ntp -f standalone/pom.xml -Pnative-fork,native-dawn \
  -Dchromellm.native.source=/absolute/path/to/pinned/llama.cpp \
  -Dchromellm.native.webgpu=ON verify
```

For download and CMake-package discovery without a native source checkout,
`mvn -B -ntp -f standalone/pom.xml -Pnative-dawn generate-sources`
only stages Dawn. It does **not** build an inference engine.

The installer selects **Linux x86_64** or **macOS arm64**, then downloads
the corresponding pinned GitHub release ZIP. Exactly these immutable asset
names and published GitHub release SHA-256 digests are admitted:

| Platform | Release archive suffix | SHA-256 |
| --- | --- | --- |
| Linux x86_64 | `ubuntu-latest-Release.zip` | `a5a87d9513e1fc109c355628baebe6b85fe7aa258f8ed2d2cf71ddc902e778bb` |
| macOS arm64 | `macos-latest-Release.zip` | `3e8d604c4b601b07cc0895396507927f2b52db60022276e435fb21bba590cfa9` |

Both names begin with
`Dawn-5e9a4865b1635796ccc77dd30057f2b4002a1355-`.
The original upstream archives contain a nested `.tar.gz`; the installer
checks its exact entry name, extracts it using the host `tar`, and verifies
the existence of `DawnConfig.cmake`. CMake discovers the staged
`standalone/target/native-deps/dawn` prefix without changing global
machine state. Downloads use HTTPS, a bounded response size, the exact
published SHA-256, and no unpinned `latest` lookup. The verified archive is SHA-256-checked on every run. The installed prefix
is sealed by a deterministic digest of its extracted files, paths, and symlink
targets; a subsequent build checks that digest before reuse. Mismatched or
incomplete caches fail closed and require deliberate cleanup of the Maven
target directory; there is no silent repair, CPU fallback, unpinned version
change, or browser-provided DLL substitution.
The installer does not redistribute native files inside the JAR.

The Maven profile intentionally does not run on Windows: this pinned
upstream release has no corresponding Windows asset. Windows users may
still build CPU JNI or supply a separately qualified local Dawn installation;
the automatic downloader explicitly refuses unsupported hosts.

GitHub Actions tests the actual Dawn download, checksum, extraction, CMake
package discovery and supported host matrix. These checks establish dependency
availability, **not** GGUF inference, GPU offload, semantic parity, or the
license status of any operator-supplied model. Fork source must still be
explicitly supplied; it is not cloned implicitly by the Dawn downloader.

## Synthetic JNI test build

For CI and contract development without a model or donor checkout:

```sh
mvn -B -ntp -f standalone/pom.xml -Pnative-synthetic verify
```

This builds the existing **test-only** JNI stub into `standalone/target/native-synthetic`, runs its CTest library-discovery regressions, and runs the Java contract suite. On Unix, the JNI bridge is `libchromellm_llama_jni.so` (Linux) or `libchromellm_llama_jni.dylib` (macOS); on Windows it is normally under `Release/chromellm_llama_jni.dll`. GitHub Actions separately invokes `JniSyntheticContractTestMain` with that exact library, including cancellation and allocation-limit checks. Neither the synthetic profile nor its CI checks assert real model correctness.

## Windows dependent DLL resolution

The synthetic JNI fixture builds two separate native artifacts on Windows:
`chromellm_llama_jni.dll` and its linked `chromellm_llama_stub.dll`.
Passing the absolute JNI bridge path to `System.load` does not by itself
guarantee that the Windows loader will find the bridge's dependent DLLs.
Before launching Java, add the **build's** `Release` directory (containing
both DLLs) to the Windows process `PATH`. The GitHub Actions Windows matrix
publishes that directory to `GITHUB_PATH` after Maven's native build, so
the subsequent JVM steps inherit it; no DLLs are copied or committed.
Do not rely solely on `-Djava.library.path` for transitive DLL dependencies.

For a real source-built fork, inspect its actual linked native dependency
locations rather than assuming that all GGML/Dawn libraries share the JNI
directory. This CI loader repair is a synthetic fixture contract, **not**
proof of a distributable native runtime. See the Windows job logs for
acceptance of the end-to-end `JniSyntheticContractTestMain` stage.

## Real model verification

Add `real-model-test` to a native build to download and verify the pinned test GGUF
and execute CPU JNI inference. With `native-fork`, set
`-Dchromellm.real.library=/absolute/path/to/standalone/target/native-fork/libchromellm_llama_jni.so`
on Linux. The commit-validated `fork-native` profile defaults to
`standalone/target/fork-native`; see [MAVEN_MODEL_TEST.md](MAVEN_MODEL_TEST.md).

## Admission and boundaries

- The default `mvn -f standalone/pom.xml verify` is unchanged: Java-only, no CMake, network, model or native build prerequisite.
- Choose exactly one native build profile: `native-fork`, commit-validated `fork-native`, or `native-synthetic`. Maven's `validate` stage fails when any two are activated together, **before** CMake or native artifact writes. The optional `native-dawn` dependency profile accompanies `native-fork`; use `dawn-binary` with commit-validated `fork-native`, and select only one Dawn provisioning owner.
- The operator pins and audits the donor source, native dependencies and model licenses. The local build does not copy native artifacts or weights into the Java JAR.
- Source-build acceptance requires an independently supplied real model, runtime observation, licensing review, the supported OS and (when selected) measured WebGPU device execution. A passing Maven/CTest/synthetic JNI build does **not** supply these proofs.
- Canonical source mutation, qualification receipts and evidence belong in `hsoliwal/com.synexia`, not ChromeLLM. M3JDK21 integration remains a separate, explicitly qualified receiving change.

## Attach a platform native distribution

Both existing real-source profiles can attach a separate native runtime ZIP:

```sh
mvn -f standalone/pom.xml -Pnative-fork,native-dist \
  -Dchromellm.native.source=/absolute/path/to/qualified/source \
  -Dchromellm.native.classifier=linux-x86_64 \
  -Dchromellm.native.notices=/absolute/path/to/reviewed-notices install
```

The pinned source entry point also supports `-Pfork-native,native-dist` with
its existing `chromellm.fork.source` and `chromellm.fork.commit` properties.
The explicit classifier must match the build host: `linux`, `macos` or
`windows`, followed by `x86_64` or `arm64`. This is a host build, not a cross
compilation request. The attachment uses the Java artifact's group, name
and version, type `zip`, and the supplied classifier. Maven `install` and
`deploy` therefore handle the JAR and native ZIP together. The JAR remains
Java-only. Normal builds and `native-synthetic` never attach a native ZIP;
`native-dist` refuses synthetic and prebuilt-library modes.

CMake stages the linked JNI runtime closure under a fresh target directory,
retaining library names and soname symlink chains. Unresolved libraries,
test stubs and non-baseline runtime libraries outside the producer build tree
fail packaging. This lane disables donor OpenMP to avoid silently depending on
or redistributing an unqualified compiler OpenMP runtime; ordinary native builds
retain their existing OpenMP selection. Shared external BLAS/Dawn/provider
libraries require a separately qualified source-linked runtime. The packager
does not silently copy arbitrary external dependencies. The archive contains `lib/` and
`licenses/`, including the supplied source's LICENSE, ChromeLLM notices, and the explicit
`chromellm.native.notices` directory. This nonempty directory must contain
operator-reviewed notices for all compiled and linked native code, including
embedded source notices such as Mozilla's llamafile license. Directory presence
is enforced; automated license completeness is not claimed. The archive
contains no model, checkout, headers or CMake build tree. The producing
operator must still review licenses for any additional linked dependency
and qualify CPU instruction requirements and host ABI compatibility.
The platform classifier alone does not establish those qualifications.

Extract the ZIP before use and pass the absolute bridge path through the
existing `--jni-lib` option. On Unix the staged libraries use relative
loader paths. On Windows add the extracted `lib` directory to process
`PATH` before starting Java so dependent DLLs can be resolved. System runtime
libraries are supplied by the receiving OS; they are not bundled. Optional
GPU drivers and model weights retain their existing separate requirements.

The native contract suite builds a small versioned dependency and JNI
bridge, archives and extracts them, deletes all producer binaries, then
loads the extracted bridge in a fresh JVM. It separately checks missing
runtime dependency, test-stub, missing-source and invalid-classifier refusal.
These are packaging/loader checks, independent of model quality or GPU
execution. The test fixture is never attached as a Maven distribution.

After `install`, the independent smoke consumer under
`standalone/native/tests/consumer/pom.xml` resolves the JAR and classified ZIP
from the Maven repository. Copy that POM into an empty writable directory,
then invoke Maven `validate` with `-Dnative.classifier=linux-x86_64` and
`-Dnative.bridge=/absolute/consumer/lib/libchromellm_llama_jni.so` (adjust the
bridge filename and Windows process PATH for the host). The consumer extracts
the resolved archive and invokes the existing model-free device inventory.
For a relocation check, move the producer build directory out of its original
path before invoking the consumer. Existing Dawn CI keeps its source-build
and inventory checks; it does not assert a distributable license closure.
A real distribution CI run must supply the reviewed source-derived notices.
Successful packaging and loading do not imply model or physical GPU inference.

The current optional installed-Chrome Java provider's Apache-2.0 notice and
license are retained in `licenses/ChromeLLM/standalone/NOTICE.md` and
`licenses/ChromeLLM/LICENSES/Apache-2.0.txt`, so the bundled THIRD_PARTY links
remain usable. This preserves current attribution; the native archive does
not include Chrome, Chrome model assets, or that provider's Java classes.
