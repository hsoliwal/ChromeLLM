# ChromeLLM lazy local model contract (opt-in)

The standalone Java/JNI runtime stays browser-free. Legacy `--model` and tier-specific
paths remain authoritative. A local CLI or JNI invocation may instead supply all
three options `--model-url`, `--model-sha256`, and `--model-cache-dir`; these are
exclusive with `--model`, `--tier`, and all tier-specific paths. The cache directory
must already exist and be writable; acquisition never creates an implicit user-home
cache. Hosted Gemini rejects these options.

`--model-url` must be an explicitly supplied HTTPS URL, without user-info or fragment.
`--model-sha256` is a 64-hex-character digest of the entire licensed GGUF artifact.
The model is fetched no earlier than the first inference request, never at build
or argument parse time. The CLI executable or JNI bridge remains separately installed.
The caller must review model redistribution, export, and use rights.

The implementation stores verified model bytes as `<sha256>.gguf` in the specified
cache. Every cache hit is SHA-256 checked, a corrupt cache entry fails closed,
and an incomplete or mismatched download is removed without replacing existing
unowned paths. Missing artifacts use HTTPS GET with redirects disabled; transfer
size is bounded to 64 GiB; transient failures have no automatic retry or provider
fallback. Files are staged in the same directory and published via an exclusive
hard link only on successful verification. Directory creation, implicit writes
to home/root, and silent repair of corrupted cached artifacts are forbidden.

The artifact is acquired before native or subprocess model initialization. The
request's inference timeout applies to inference after successful acquisition.
Acquisition has a separate one-hour deadline covering coordination, cache verification,
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
