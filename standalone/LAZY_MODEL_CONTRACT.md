# Lazy native runtime and model acquisition

ChromeLLM can acquire the configured JNI library and model on first inference. The
same provider is used by the command line, `SmartBuildMain`, and the public Java
factory `StandaloneMain.openBackend(String... args)`. Constructing or closing an
unprepared provider performs no network, cache, or native-library work.

## Artifact selection

Existing `--jni-lib`, `--model`, and tier-specific local paths remain supported.
For a remote model, supply `--model-url`, `--model-sha256`, and
`--model-cache-dir`. An optional `--model-bytes` adds an exact length check.
Model URL options are exclusive with local model and tier selections.

For a remote JNI library, select `--backend jni` and supply all four of
`--jni-url`, `--jni-sha256`, `--jni-bytes`, and `--jni-cache-dir`.
These options are exclusive with `--jni-lib`. The artifact must be a complete,
platform-compatible shared JNI library; archive extraction and discovery of additional
native dependencies are not part of this loader. Its non-system dependencies must
already be linked or packaged by the native build. Model and JNI artifacts have
independent digests and version identities.

URLs must use HTTPS without credentials or fragments. SHA-256 pins contain 64 hex
digits. Length pins must be positive and bounded to 64 GiB. Partial descriptors,
contradictory local/remote selections, invalid compute policies, and invalid numeric
parameters are rejected before acquisition. Hosted Gemini and installed-Chrome
providers reject these native acquisition options.

The explicitly configured cache directory is created on first online acquisition if
necessary. No implicit user-home location is chosen by the provider. Direct internal
cache callers retain their existing requirement to provide an existing directory.
Verified models are stored as `<sha256>.gguf`; raw native artifacts use
`<sha256>.bin`. No downloaded binary is loaded until every configured artifact has
passed its size and digest checks.

## Online, cached, and offline execution

`--offline true` permits only reverified cache hits. It performs no network operation
and creates no missing cache directory. An absent, corrupt, non-regular, or
size-mismatched entry is refused. Online execution also refuses corrupt existing
entries instead of silently replacing them. A successful initialized provider reuses
its model and native session across serial requests. A new provider checks the cached
bytes again before opening its session.

For `SmartBuildMain`, the properties keys are `jniUrl`, `jniSha256`, `jniBytes`,
`jniCacheDir`, `modelUrl`, `modelSha256`, `modelBytes`, `modelCacheDir`, and `offline`.
The existing `jniLib` and `model` keys remain valid. Relative paths resolve from the
standalone project. Maven's offline setting takes precedence over `offline=false` in
a recipe. Offline Smart Build preflights both caches before opening its provider.
The target directory is validated before cache or provider work. Results remain
review-only candidates under the existing Maven `target/smart-build` directory.
The original three-argument entry point and the newer optional fourth offline
argument (`true`, `false`, `--offline=true`, or `--offline=false`) remain supported.
Canonical workspace-ancestor aliases are accepted; target/output symlinks are refused.

The explicit Maven `real-model` and `real-model-test` profiles retain their real
GGUF CPU inference gates. The latter resolves the pinned model during integration-test
in its explicitly configured directory, defaulting to
`${settings.localRepository}/io/synexia/chromellm/models`. These gates verify the
artifact and perform real JNI inference; ordinary offline verification does not
acquire licensed weights or load that native model.

The public Java factory accepts provider options only. A caller supplies the prompt,
token limit, and inference timeout through `ModelRequest`, and closes the returned
`ModelBackend` after use. The factory uses the same selection and acquisition owners
as the command line; it does not run Maven, edit source, or introduce a second runtime.

## Transfer and publication

Each production download permits at most five validated HTTPS redirects. Downgrades,
credentials, and fragments are refused at every hop; the digest covers the final
bytes. Content encoding must be identity. Intermediate responses and the final
HTTP client/stream are closed. The HTTP-client compatibility seam uses that same
owned transport and deadline. The response-sender test seam retains the incoming
cycle, ambiguous-location, encoding, and size refusal contracts without opening sockets.

Files are staged in the destination directory and published through an exclusive
hard link after verification. Failed or incomplete transfers remove their staging
file without replacing existing paths. Transfer limits are checked while streaming;
exact length and digest are checked again before native/model initialization.
Transient failures have no automatic retry or provider fallback.

Within a JVM, callers acquiring the same canonical cache path and digest serialize
cache recheck, transfer, and publication. Waiting callers reuse the verified result
and can cancel independently. Different identities proceed independently. Only active
acquisitions retain coordination gates. Separate JVMs may transfer concurrently;
exclusive publication and verification still prevent overwrites.

## Deadlines, cancellation, and lifetime

Each artifact acquisition has a one-hour deadline covering coordination, cache
verification, connection establishment, redirects, and the complete response body.
The inference timeout applies after successful acquisition. A timed-out waiter returns
an HTTP timeout error. Interruption cancels its acquisition worker and closes its
active stream asynchronously; staging cleanup follows worker completion. An
uncooperative transport or filesystem may delay cleanup. Cancellation before the final
publication admission prevents publication. An already-admitted verified publication
may finish afterward, but no provider opens for that cancelled request.

Preparation snapshots expose state and processed-byte count without I/O. With two
artifacts the count sums their latest observations and may restart during re-verification.
The state follows the current artifact, then provider initialization and readiness.
A cancelled older attempt cannot replace a newer attempt's progress.

Closing a lazy backend marks it closed and wakes queued callers immediately.
Acquisition of either artifact is interrupted without waiting for completion. A
provider returned after closure is released once and receives no inference. An
already-admitted inference retains its provider until it unwinds; native handles are
never disposed concurrently with that call. Cleanup failures remain suppressed under
the primary failure. Close retains verified cache contents.

The optional MCP `backend_status` tool exposes the same lifecycle snapshot. CLM1,
MCP, and ordinary Java calls continue to use the existing native provider. Actual
model inference, binary compatibility, and physical Dawn/GPU execution require their
respective runtime tests; byte acquisition alone does not establish those outcomes.
