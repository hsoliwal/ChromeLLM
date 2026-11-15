# Opt-in MCP stdio server contract

ChromeLLM's existing `--stdio true` endpoint remains binary CLM1 and is **not MCP**.
The new `--mcp-stdio true` entry point is exclusive with `--stdio true`,
`--list-devices true`, and `--prompt-file`. It uses the existing explicitly
selected `ModelBackend` and the same path-based or SHA-256 pinned lazy model
selection. No model, browser, Node runtime, or third-party Java library is bundled.

## Transport and protocol

- MCP JSON-RPC 2.0 stdio; one JSON document per LF-terminated UTF-8 line.
- The server supports version `2025-06-18` and announces its supported version
  in the `initialize` reply. Future versions require explicit qualification.
- `initialize` -> `notifications/initialized` -> `tools/list` / `tools/call`.
- Also supports `ping` once initialized. Unknown request methods return
  JSON-RPC -32601. Unknown tool names and malformed arguments return -32602.
- Tool execution errors return MCP tool results with `isError: true`, not a
  transport-level protocol failure. No prompt or credential material in error text.
- Requests and replies have bounded byte lengths and strict UTF-8 and JSON
  validation, including duplicate-key rejection. Malformed frames fail closed.
- A normal input EOF closes the selected backend. Stderr is the only diagnostic
  stream; stdout carries JSON-RPC frames only.
- No automatic download unless a local model was explicitly configured with
  URL + SHA-256 + approved writable cache and a tool actually requests inference.
- The server does not silently discover tools, execute arbitrary commands,
  accept runtime-supplied URLs, or dynamically switch providers.

## Registered tools

| Tool | Arguments | Result |
| --- | --- | --- |
| `backend_status` | none | Selected backend ID; no paths, API keys or prompt data |
| `infer_text` | `prompt` required, `maxTokens` optional (1..32768, default 512), `timeoutSeconds` optional (1..3600, default 120) | Plain model text candidate; no claim of factual correctness |

Model execution uses the same bounded `ModelRequest` and `ModelResponse` contracts.
The MCP server is synchronous and serves one tool request at a time. No HTTP or
SSE listener, ambient authentication, elevated file access, or subprocess tool
execution is introduced.

WebMCP is a different, browser-page tool registry. An opt-in website integration
must use `document.modelContext.registerTool()` on an actual page, with browser
permissions and user confirmation for consequential operations. This MCP server
must not be advertised as a WebMCP implementation.
