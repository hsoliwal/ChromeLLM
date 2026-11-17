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

JNI without `--jni-lib` or the native URL quartet uses the matching installed
platform native JAR through `NativeLibraryLoader`. That existing packaged loader
verifies the resource manifest and every bundled dependency before loading, and
does not download native code. Its extraction cache defaults to
`${user.home}/.cache/chromellm/native` and can be selected with
`-Dchromellm.native.cache`. Packaged discovery and extraction for a remote model
remain deferred until first inference. The raw URL path continues to use its
explicit `--jni-cache-dir` and never treats a downloaded archive as a native JAR.

URLs must use HTTPS without credentials or fragments. SHA-256 pins contain 64 hex
digits. Length pins must be positive and bounded to 64 GiB. Partial descriptors,
contradictory local/remote selections, invalid compute policies, and invalid numeric
parameters are rejected before acquisition. The hosted Gemini provider rejects
these native acquisition options; the installed-Chrome provider has been removed.

The explicitly configured cache directory is created on first online acquisition if
necessary. No implicit user-home location is chosen for URL artifacts. Direct internal
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
The model policy keys are `modelFollowRedirects`, `modelDownloadTimeoutSeconds`,
and `modelMaxBytes`. For URL recipes, `modelCacheDir` defaults to `target/model-cache`.
Maven creates a managed cache beneath its verified target directory after provider
validation, immediately before inference. For a recipe using an existing CLI or
local/packaged JNI runtime and an explicit external model cache, Smart Build validates
all options and the cache's existing ancestors before creating the directory and
opening the provider. Symlink ancestors are refused before writes. Recipes with a
raw JNI URL retain their external model and native cache directories under the
artifact owner's first-inference lifecycle.
Offline execution creates none of these cache directories.
The existing `jniLib` and `model` keys remain valid. Relative paths resolve from the
standalone project. Maven's offline setting takes precedence over `offline=false` in
a recipe. Offline Smart Build preflights both caches before opening its provider.
The target directory is validated before cache or provider work. Results remain
review-only candidates under the existing Maven `target/smart-build` directory.
The original three-argument entry point and the newer optional fourth offline
argument (`true`, `false`, `--offline=true`, `--offline=false`, `offline=true`, or
`offline=false`) remain supported.
When present, the fourth argument is the authoritative Maven offline setting.
Three-argument callers retain the legacy `chromellm.offline` system-property policy.
A recipe with `offline=true` further restricts either invocation.
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

Legacy model selections without the three model-policy fields retain up to five
validated HTTPS redirects, including CDN origins. Supplying
`--model-follow-redirects`, `--model-download-timeout-seconds`, or `--model-max-bytes`
selects explicit policy: redirects default to false, and only a GitHub release URL
of the form `https://github.com/<owner>/<repo>/releases/download/<tag>/<asset>` may
follow at most three redirects among GitHub release URLs and
`release-assets.githubusercontent.com`, using the default HTTPS port.
`--model-follow-redirects true` permits up to five validated HTTPS redirects.
The native binary provisioning factory applies the three-hop release policy to
GitHub release origins and retains checked generic HTTPS redirects for other
explicit origins. Raw `--jni-url` acquisition and the legacy generic HTTP seams
retain the five-hop HTTPS policy. All paths share one transport and body-lifetime
owner.

Downgrades, credentials, fragments, cycles, and blank or ambiguous redirect locations
are refused; requests forward no cookies or authorization. The digest covers the final
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

Each artifact acquisition defaults to a one-hour deadline covering coordination, cache
verification, connection establishment, redirects, and the complete response body.
Models can select a smaller whole-acquisition deadline through
`--model-download-timeout-seconds` (1–3600) and a smaller byte bound through
`--model-max-bytes` (1–64 GiB). Cached models are subject to that byte bound too.
An optional exact model size pin further restricts the streamed and cached length.
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
