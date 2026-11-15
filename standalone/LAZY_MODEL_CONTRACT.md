# ChromeLLM lazy local model contract (opt-in)

The standalone Java/JNI runtime stays browser-free. Legacy `--model` and tier-specific
paths remain authoritative. A local CLI or JNI invocation may instead supply all
three options `--model-url`, `--model-sha256`, and `--model-cache-dir`; these are
exclusive with `--model`, `--tier`, and all tier-specific paths. The cache directory
must already exist and be writable for the direct CLI; acquisition never creates
an implicit user-home cache. Explicit Maven binary profiles prepare their documented
cache directories. Smart-build creates only selected caches under the verified
project target directory. Hosted Gemini rejects these options.

`--model-url` must be an explicitly supplied HTTPS URL, without user-info or fragment.
`--model-sha256` is a 64-hex-character digest of the entire licensed GGUF artifact.
The model is fetched no earlier than the first inference request, never during
argument parsing or ordinary compilation. An explicitly selected Maven `smart-build`
profile runs inference during verify and can therefore acquire the model then.
The explicit `real-model` profile may provision its pinned model before verification.
The `real-model-test` profile resolves its selected model during integration-test;
its configured cache defaults to `${settings.localRepository}/io/synexia/chromellm/models`.
Both explicit Maven paths verify cache hits and honor offline mode.
The CLI executable remains separately installed; JNI can use an explicit library
path or the matching packaged native artifact.
must already exist and be writable for the direct CLI; acquisition never creates an
implicit user-home cache. The explicitly selected Maven binary profiles and smart-build
entrypoint prepare their documented cache directories. The opt-in `real-model-test`
profile creates its explicitly configured directory, defaulting to
`${settings.localRepository}/io/synexia/chromellm/models`. Hosted Gemini rejects these options.

`--model-url` must be an explicitly supplied HTTPS URL, without user-info or fragment.
`--model-sha256` is a 64-hex-character digest of the entire licensed GGUF artifact.
The lazy provider fetches the model no earlier than the first inference request and
never at argument parse time. An explicitly selected Maven `real-model` profile may
provision its pinned test model before verification. The explicit `real-model-test`
profile resolves its pinned model during `integration-test`, then executes real JNI
inference. The CLI executable or JNI bridge
remains separately installed or built from the selected native source.
The caller must review model redistribution, export, and use rights.

The implementation stores verified model bytes as `<sha256>.gguf` in the specified
cache. Every cache hit is SHA-256 checked, a corrupt cache entry fails closed,
and an incomplete or mismatched download is removed without replacing existing
unowned paths. Missing artifacts use HTTPS GET. An explicit GitHub release-download
URL may follow up to three HTTPS redirects within GitHub release-download endpoints
and `release-assets.githubusercontent.com`, on the default HTTPS port only and
without user-info or fragments. Other redirects remain refused by default.
`--model-follow-redirects true` explicitly permits at most five HTTPS redirects,
with no user-info, fragments, or HTTPS downgrade at any hop. Cookies and authorization
headers are never forwarded. Redirected responses are closed before the next request;
the complete pinned SHA-256 is checked after transfer. Transfer size defaults to 64 GiB and may be reduced
using `--model-max-bytes`. Transient failures have no automatic retry or provider
fallback. Files are staged in the same directory and published via an exclusive
hard link only on successful verification. The cache owner does not create directories or write implicitly to home/root,
and never silently repairs corrupt entries. The Maven smart-build wrapper may
create a selected cache under the verified project target directory. Its relative
paths resolve against the standalone project; external cache paths must exist.
Maven offline mode revalidates cached binary inputs and refuses misses before
opening the downloader. Exact-size native binary artifacts use `.bin` cache entries;
GGUF model entries retain the `.gguf` suffix.
unowned paths. Missing artifacts use HTTPS GET.

Pinned native binary acquisition distinguishes an explicit URL of the form
`https://github.com/<owner>/<repo>/releases/download/<tag>/<asset>` on the default
HTTPS port may follow up to three HTTPS redirects within GitHub release-download
endpoints and `release-assets.githubusercontent.com`, also on the default HTTPS
port. General model acquisition and other explicit HTTPS origins retain up to five
validated HTTPS redirects. The native-binary GitHub release lane retains its
three-hop endpoint allowlist; both lanes use the same bounded lifecycle engine.
Every hop rejects downgrades, user-info and fragments; redirect cycles and blank or
ambiguous locations fail. Redirects carry no cookies or authorization. Redirected
responses are closed before the next request, and the full pinned SHA-256 is checked
after transfer. Transfer size is bounded to 64 GiB, or the smaller caller-supplied
bound; transient failures have no automatic retry or provider fallback. Files are staged in the same directory and published via an
exclusive hard link only on successful verification. The cache owner itself does
not create directories or silently repair corrupt artifacts. Maven offline mode
revalidates existing binary inputs and refuses cache misses before opening the downloader.

The artifact is acquired before native or subprocess model initialization. The
request's inference timeout applies to inference after successful acquisition.
Acquisition has a separate deadline, configurable with `--model-download-timeout-seconds`
(1–3600 seconds, default 3600), covering coordination, cache verification,
connection establishment and the complete streamed response. The waiting caller returns
with an HTTP timeout error if that deadline expires. Interruption cancels only that caller's
attempt. Cancellation interrupts its worker and closes its active stream asynchronously;
staging cleanup follows when the worker unwinds. An uncooperative transport or filesystem
can delay cleanup. Cancellation that wins the final publication-admission check prevents
publication. An already-admitted, verified hard-link publication may finish after cancellation;
that artifact may remain cached, but no new provider is opened for the cancelled request.
Filesystem operations never hold the cancellation monitor. Cleanup failures are suppressed
under the original acquisition or inference failure rather than replacing it.

Within one JVM, acquisitions for the same canonical cache path and digest serialize their
cache recheck, transfer and publication. A waiting caller reuses the verified result and
can cancel without interrupting the owning caller. Different digests proceed independently.
Only active callers retain coordination gates. Separate JVMs may still transfer concurrently;
exclusive hard-link publication and digest verification continue to prevent overwrites.

Preparation snapshots are immutable observations with a state and processed-byte count.
The states distinguish unprepared, waiting, verifying, downloading, available artifact,
initializing provider, ready provider, failure, cancellation and closure. Byte counts apply
to the current verification or download phase and can restart at a phase change. Reading
a snapshot performs no disk, network or native work. The snapshot describes the most recently
started attempt; an older cancelled worker cannot overwrite a newer attempt's status.

Closing a lazy backend marks it closed and wakes queued callers immediately. Preparation
is interrupted without waiting for download or native-open completion. A provider returned
after closure is released once and receives no inference call. An already-admitted inference
retains its provider until it unwinds; native handles are never disposed concurrently with
that call. Close does not delete verified cache contents. Model downloads still require the
configured HTTPS URL and digest; this capability does not establish standalone support for
Chrome-managed Gemini Nano artifacts.

The optional MCP JSON-RPC stdio transport exposes the same native lifecycle. Its
`backend_status` tool retains the provider-ID text and adds a structured preparation
snapshot for lazy local backends. The server processes requests sequentially; this is a
snapshot response, not asynchronous progress notifications. WebMCP remains a separate,
optional browser adapter. The existing CLM1 stdio transport and public Java/JNI contracts
remain independent of a browser. This stage qualifies model acquisition and lifecycle
management in the existing providers. Extracting Chrome's runtime through the forks into
portable, Maven-built Java/JNI outputs remains the larger goal; the current GGUF provider
is one intermediate implementation. Model-specific runtime integration, resumable transfer
and physical Dawn/GPU qualification require their own acceptance evidence.
