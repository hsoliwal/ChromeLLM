# Standalone agent planning contract (opt-in, candidate-only)

ChromeLLM's existing `ModelBackend` remains the sole inference port. The opt-in MCP
`plan_steps` tool asks that selected backend for **one** JSON plan and validates
it before returning any proposed steps. It does not execute the steps, run a
shell, access the filesystem, mutate a repository, or switch providers. Its
implementation uses Java 21 only and does not require Chrome, Node, WebMCP,
Maven during inference, a remote service, or native JNI for the validation
stage. A caller may explicitly select the existing native JNI backend.

## Request and response

`tools/call` with `name: "plan_steps"` accepts `arguments` containing:

- Required `task`: nonblank UTF-8 text with at most 4096 encoded bytes.
- Optional `maxTokens`: integral 1..32768 (default 1024).
- Optional `timeoutSeconds`: integral 1..3600 (default 120).
- Unknown arguments are rejected before any provider invocation.

The selected model receives a bounded instruction to return exactly one JSON
object with a `steps` array. Each item must provide:

```json
{"id":"inspect","tool":"backend_status","dependsOn":[],"arguments":{}}
```

An `infer_text` step instead accepts `arguments.prompt` (nonblank, at most
8192 UTF-8 bytes), optional integral `maxTokens` (1..32768) and optional
integral `timeoutSeconds` (1..3600). The only allowed `tool` values are
`backend_status` and `infer_text`; no arbitrary MCP tool, browser action,
HTTP request, source-file mutation, or executable command is admitted.

Each plan contains 1..16 steps with unique ASCII IDs matching
`[A-Za-z][A-Za-z0-9_-]{0,31}`. Dependency IDs must exist, be unique per step,
and not form a cycle. Duplicate JSON fields, unknown fields, invalid
arguments, unsupported actions, too-large inputs, malformed UTF-8, cycles
and unresolved edges are refused. Dependency order is resolved by Kahn's
algorithm using a lexicographically ordered ready queue; ties are stable
regardless of input order. Complexity is O((V+E) log V), with V <= 16.

A successful MCP response contains `content` (JSON plan text) and
`structuredContent` with an ordered `steps` array. The result is a
**candidate plan**, not a verified action trace or an execution receipt.
Any provider failure, identity mismatch, prompt digest mismatch, invalid plan
or interruption returns a tool-level error with no partial plan and no fallback.
Raw model errors and prompts are not included in externally visible errors.

## Model and provenance boundaries

The backend ID must agree with the existing selected `ModelBackend.id()`.
The backend-supplied prompt SHA-256 must equal SHA-256 of the exact inference
prompt. The backend is invoked once per admitted request; plan validation
does not invoke it again. Temperature zero is not proof of determinism;
canonical ordering and strict acceptance are deterministic **given identical
candidate JSON**.

ChromeAIAgent (guberm/ChromeAIAgent, MIT) is a design donor for intent/plan
separation. No donor source is copied. Algorithmic evaluation donor problems:

- LeetCode 207/210, Course Schedule / Course Schedule II: cycle detection and
  topological ordering (Kahn/DFS). Prefer Kahn with a lexical priority queue
  for reproducible emitted steps.
- HackerRank Components in a Graph: disjoint-set component queries are
  unsuitable for *directed* prerequisite order and cycle detection.
- LeetCode 743, Network Delay Time: Dijkstra is unnecessary because this plan
  has dependency order, not weighted shortest paths.

Product/source files live in this repository. M3 transformation recipes,
qualification and benchmark evidence belong only in `hsoliwal/com.synexia`,
as required by the project AGENTS.md. Any future autonomous executor must
introduce independent admission, permissions, tool-result verification and
replay rules before any filesystem or process mutation is allowed.

## Verification

Run `mvn -B -ntp -f standalone/pom.xml verify`. The default Maven verification
runs the agent planning contract tests with synthetic, no-network providers.
No JNI model output quality, hardware acceleration or end-to-end autonomous
Maven build is claimed from these tests.
