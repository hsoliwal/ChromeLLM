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
The HTTPS connection/request has fixed JDK timeouts, but reading its streamed body
is not guaranteed to obey an end-to-end wall-clock deadline; interruption is
supported by the JDK transport. Close does not delete verified cache contents. This opt-in feature does not download proprietary
Chrome-managed Gemini Nano artifacts, which are not portable GGUF weights.

WebMCP and MCP are separate capabilities: WebMCP tools are declared by a browser
page and exist only while it is open; a standalone backend MCP server would require
a distinct JSON-RPC transport. This change adds neither browser dependencies nor
claims MCP protocol compliance for ChromeLLM's existing custom stdio protocol.
