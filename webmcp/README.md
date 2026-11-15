# ChromeLLM browser WebMCP adapter

This optional browser-side adapter is **not** the standalone Java MCP stdio
server. WebMCP is a browser page tool registry; MCP stdio is backend JSON-RPC.
No browser code is imported into Java/JNI, Maven, llama.cpp, or native builds.

`registerChromeLlmTools({ backendStatus, inferText, confirmInference })`
is a standalone ES module; call it from an actual user-facing browser page
after establishing a secure authenticated backend adapter. On supporting
Chromium browsers it registers two tools through
`document.modelContext.registerTool()`:

- `chromellm_backend_status` returns only the selected provider ID. Reading
  status never downloads a model or triggers inference.
- `chromellm_infer_text` validates bounded arguments, obtains explicit
  in-page user confirmation, and then calls the injected `inferText`
  function. The injection supplies authorized transport to a selected
  backend and MUST implement its own authentication, CSRF, origin controls,
  request admission, and cost visibility.

Tool arguments cannot specify an arbitrary model URL, cache location,
binary path, native library, or provider. Model selection is owned by the
explicitly configured backend. No token, URL, credential, or native model
artifact is embedded or copied to the page.

The adapter feature-detects WebMCP; unsupported browsers are a no-op.
It uses an AbortController to unregister tools, and propagates each call's
AbortSignal to the injected callbacks. Registration and execution are
separate operations. Calling dispose does not imply that an already running
model operation is killed; the host must honor its cancellation signal.
An error is returned as an explicit structured error result, not hidden
by falling back to another provider.

The callbacks and confirmation UI are intentionally not implemented here:
the repository does not yet contain a website or a secure browser-facing
gateway to the standalone MCP stdio subprocess. Adding an HTTP endpoint
solely to make a demo work would introduce an undeclared security boundary.
The supplied injectable adapter is verified with offline Node tests; browser
feature availability and real browser-agent execution require separate
browser integration qualification.
