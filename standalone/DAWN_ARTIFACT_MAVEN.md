# Opt-in pinned Dawn artifact provisioning for Maven

ChromeLLM's default Java 21 `verify` build remains offline. The independently
supplied `hsoliwal/synexia-llmcpp` fork is still compiled through the existing
JNI/CMake integration. The new **`native-webgpu-artifact`** Maven profile adds
one operation before native compilation: download and unpack an **explicitly
SHA-256-pinned public Dawn native install archive** (direct `.tar.gz` or
GitHub Release `.zip` containing a single `Dawn-*.tar.gz`). It never launches Chrome,
extracts Chrome-managed weights, or silently selects a hosted model provider.

## Reusable public Dawn package

The inspected `hsoliwal/dawn` Actions workflow builds a
`Dawn-<revision>-<platform>-Release.tar.gz` install archive; the release
workflow aggregates those artifacts for publication. At inspection time the
repository's GitHub Releases API returned **no published releases**. Therefore
there is **no hard-coded URL or guessed digest** in this profile. The operator
must first publish/select a compatible build and record its true archive SHA.

```sh
mvn -B -ntp -f standalone/pom.xml -Pnative-webgpu-artifact verify \
  -Dchromellm.native.source=/approved/src/synexia-llmcpp \
  -Dchromellm.dawn.url=https://github.com/OWNER/REPO/releases/download/TAG/Dawn-PLATFORM.tar.gz \
  -Dchromellm.dawn.sha256=THE_REAL_64_HEX_SHA256
```

The native source checkout remains separately provisioned and audited. The
`native-webgpu-artifact` profile is **exclusive** of `native-fork` and
`native-synthetic`, as those configure separate native build contracts. The
artifact is downloaded only when this profile is selected. The existing
`--model-url`, `--model-sha256`, and `--model-cache-dir` runtime flags
continue to acquire a separate explicitly licensed GGUF at first inference;
no model is needed to compile the JNI bridge.

## Verified public release example

The `synexia-llmcpp` WebGPU workflow refers to a prebuilt Dawn release from
`reeselevine/dawn`. The GitHub Releases API for tag `v2.0.0` reports this
Ubuntu ZIP asset and its full SHA-256 digest:

```text
URL=https://github.com/reeselevine/dawn/releases/download/v2.0.0/Dawn-5e9a4865b1635796ccc77dd30057f2b4002a1355-ubuntu-latest-Release.zip
SHA256=a5a87d9513e1fc109c355628baebe6b85fe7aa258f8ed2d2cf71ddc902e778bb
```

The Maven provisioner verifies the **outer ZIP**, admits one named inner
`Dawn-*.tar.gz`, then safely extracts that tarball. This value was read from
GitHub release metadata, **not downloaded or executed in this session**.
It is a reproducible candidate for the manual Linux workflow; it is not a
guarantee of compatibility with any particular `synexia-llmcpp` revision
or GPU host. The `hsoliwal/dawn` fork can be used instead once it publishes
a compatible, checksum-identified release.

## Verification and filesystem boundaries

- Initial URL must be HTTPS `github.com/OWNER/REPO/releases/download/TAG/*.tar.gz`
  (or `*.tgz`, or a Release `*.zip` wrapper) without credentials, fragments or a port. A bounded number
  of explicit HTTPS redirects is allowed only to recognized GitHub release
  asset CDN hosts; redirects to arbitrary hosts, private services or HTTP fail.
- The caller provides a **full 64-hex SHA-256**; the download is bounded, streamed
  to a temporary file, verified and published without overwriting an existing
  cache entry. Cache hits are rehashed and corruption fails closed.
- Only ordinary files/directories under a single `Dawn-*` archive root are
  extracted; absolute paths, traversal, links, special tar entry types and
  excessive archive sizes/entry counts are rejected. The archive must contain
  `DawnConfig.cmake` for native `find_package(Dawn)`.
- The only persistent destination is
  `standalone/target/native-webgpu-assets/`. Re-extraction compares every
  file by SHA-256 before reusing an existing installation; divergence fails
  rather than silently overwriting it.
- The Maven profile passes the installed prefix to CMake
  as `CMAKE_PREFIX_PATH`, forces `CHROMELLM_WEBGPU=ON`, and builds
  `standalone/target/native-webgpu-jni` using the existing JNI bridge. An
  incompatible Dawn/llama.cpp ABI or unavailable GPU backend fails instead
  of falling back to CPU.
- The default `verify` runs only the **offline synthetic artifact test**;
  real release download and GPU execution require an explicitly triggered
  test with a valid published archive, usable native fork, and hardware.

An opt-in `workflow_dispatch` GitHub Actions job accepts the pinned release
URL, SHA-256 and exact native source revision. A private fork checkout requires
a repository secret with read permission, not an ambient anonymous fetch.
That workflow tests Maven provisioning/compilation but makes **no claim of
trained-model correctness or GPU device execution** unless separately measured.
