# Java coding-agent sessions

ChromeLLM can run a bounded sequence of model decisions and repository tools through
its existing Java inference provider. The `jni` provider uses the existing native
llama.cpp CPU or Dawn/WebGPU path. Lazy model acquisition, SHA-256 verification,
provider selection and native resource ownership remain with their existing owners.
The session requires Java 21 and an explicitly configured headless repository
service implementing the endpoints below.

## Run from the command line

Build the standalone JAR with `mvn -B -ntp -f standalone/pom.xml verify`. Configure
the repository service on a literal `127.0.0.1` address. Provision its 64-character
lowercase hexadecimal credential in an environment variable shared by the service
and this process. The credential is supplied by environment variable name, never
as model input or a command-line token value.

```bash
java -cp standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  io.synexia.chromellm.standalone.StandaloneMain \
  --backend jni \
  --jni-lib /absolute/path/libchromellm_llama_jni.so \
  --model /absolute/path/model.gguf \
  --context-tokens 8192 \
  --compute-backend cpu \
  --agent true \
  --agent-endpoint http://127.0.0.1:17655 \
  --agent-token-env CHROMELLM_AGENT_TOKEN \
  --prompt-file /absolute/path/goal.txt \
  --agent-max-steps 8 \
  --max-tokens 1024 \
  --timeout-seconds 120
```

Use the platform's native library filename on Windows or macOS. Existing explicit
device-selection and `--compute-backend webgpu` options remain available when the
native runtime provides them. To acquire weights at first inference, replace
`--model` with the existing `--model-url`, `--model-sha256` and `--model-cache-dir`
options described in [LAZY_MODEL_CONTRACT.md](LAZY_MODEL_CONTRACT.md). Agent setup and
MCP discovery do not trigger the first inference.

The selected model must follow the structured decision protocol. Outputs remain
candidates, and model quality depends on the chosen weights. Context byte limits
bound Java memory; the native backend independently tokenizes and checks its actual
context window. Increase `--context-tokens` only within the model/runtime's supported
capacity. A fixed temperature or a completed session does not prove the answer.

## Repository tools

The adapter reuses the headless Synexia coding-agent service. Its existing repository
index, lexical masking, source hashes and preview compiler remain authoritative.

| Tool | Existing endpoint | Behavior |
| --- | --- | --- |
| `repository_status` | `GET /api/status` | Read repository/index status |
| `repository_files` | `GET /api/files` | Read bounded inventory and truncation flag |
| `source_read` | `GET /api/file` | Read a Java file with its source hash |
| `source_search` | `GET /api/search` | Exact search over a sealed snapshot |
| `source_search_batch` | `POST /api/search-batch` | Search multiple patterns together |
| `problem_categories` | `GET /api/problem-categories` | Read algorithm evidence or search reviewed challenge categories |
| `github_build_plan` | `GET /api/github-build-plan` | Read an operator-configured build plan |
| `candidate_preview` | `POST /api/preview` | Stage an isolated diff and run structural/API/compiler checks |

Search accepts `raw` or `java-code` surfaces. The latter uses the service's existing
Java lexical admission and masking. Batch search uses its existing candidate pruning
and multi-pattern owner; this adapter does not build another index. Search returns
snapshot identities and source hashes; `source_read` obtains a fresh source seal
before a candidate preview. Preserve those identities when evaluating results.

`candidate_preview` requires `path`, `source_sha256`, `replacement`, `recipe_class`,
`recipe_source`, `before_fixture`, `after_fixture` and `rationale`. The service
validates the current source seal before staging. Its response distinguishes
compilation from recipe, test and behavior qualification. This tool has no source
application authority. A GitHub build plan is a plan, not a build execution or a
successful build result.

## Find and evaluate challenge categories

`problem_categories` with no arguments returns the existing algorithm evidence.
For category navigation, select `LEETCODE`, `HACKERRANK` or `GEEKSFORGEEKS` and
an exact category alias or prefix. These named tool decisions use the same
canonical category owner as the repository service:

```json
{"type":"tool","name":"problem_categories","arguments":{"platform":"LEETCODE","category":"binary search","limit":10}}
```

```json
{"type":"tool","name":"problem_categories","arguments":{"platform":"HACKERRANK","prefix":"dynamic","limit":10}}
```

```json
{"type":"tool","name":"problem_categories","arguments":{"platform":"GEEKSFORGEEKS","shape":"BINARY_SEARCH","limit":10}}
```

A platform alone browses its reviewed routes. A shape alone searches across all
three platforms; its name must exist in the service's canonical `AlgorithmShape`.
Choose at most one of `category`, `prefix` and `shape`. Category and prefix require
a platform and accept up to 256 UTF-8 bytes. Limits range from 1 to 64, defaulting
to 25. A limit without a selector or platform is invalid. Empty results are valid
when no reviewed route matches. `limitReached` means the returned count equals
the limit; it does not prove that more results exist.

Responses include the canonical catalogue root, reviewed URLs, algorithm shapes
and taxonomy metadata. Use those references to evaluate an algorithm's fit, then
inspect current repository sources and qualify an original candidate through
`candidate_preview`. This endpoint searches the reviewed catalogue; it does not
fetch live problem statements or solutions. Its `TAXONOMY_ONLY` authority does
not permit source copying or promotion. The service retains its existing exact
lookup, sorted prefix index and shape search; the Java/JNI adapter sends bounded
queries to that owner.

## Session protocol and limits

Each model response must be exactly one JSON object:

```json
{"type":"tool","name":"source_search","arguments":{"query":"class Example","surface":"java-code","limit":10}}
```

or:

```json
{"type":"final","answer":"Findings supported by the observed tool results."}
```

The protocol rejects extra fields, duplicate JSON keys, unknown tool names, invalid
arguments and repeated tool calls with equivalent arguments. Named fields preserve
case, Unicode and argument meaning regardless of object key order. Tools execute
serially, at most once per distinct call within a session. The last allowed model
turn must finish; a last-turn tool is refused before execution because no turn
remains to observe it.

| Control | Default | Bound |
| --- | --- | --- |
| `--agent-max-steps` | 8 model calls | 1–64 |
| `--max-tokens` | 1,024 per agent model call | 1–32,768 |
| `--timeout-seconds` | 120 for the entire session | Positive, at most 3,600 |
| `--agent-max-context-bytes` | 65,536 | 1,024 through the existing prompt byte ceiling |
| `--agent-max-tool-result-bytes` | 16,384 | Positive, at most context and response ceilings |

The full objective, tool schemas and complete observations must fit. Exhaustion
stops the session; facts are not silently truncated or evicted. Every provider and
tool receives the remaining session duration. Interruption and deadline expiry
prevent subsequent calls. Native inference and the HTTP adapter implement their own
cancellation; custom in-process providers/handlers must honor that same contract.
Each structured model decision is additionally bounded to one quarter of the
existing response ceiling, reserving room for the final answer and trace in CLI/MCP
response envelopes. Goal-file and initial-context validation happens before the
CLI opens a native model.

The HTTP adapter uses an exact operator-selected loopback authority, bypasses ambient
proxies, rejects redirects and bounds the complete response body to 128 KiB. It
requires strict UTF-8 JSON objects and rejects credential echoes. Request deadlines
cover body delivery as well as response headers. Closing the adapter cancels its
outstanding requests and shuts down its owned client.

Successful CLI output is JSON containing `status`, `answer`, `backendId`, `modelCalls`,
`toolCalls` and `trace`. Each trace entry binds the actual prompt, model response and
tool observation with SHA-256; it stores neither source text nor credentials.
`COMPLETED` means the session reached a final decision. It does not mean the answer,
candidate source, repository or build passed independent qualification.

## MCP integration

Use `--mcp-stdio true` with the same `--agent-endpoint` and `--agent-token-env`
configuration. Omit `--agent true` and `--prompt-file` in this mode. The existing
MCP `backend_status`, `infer_text` and planning-only `plan_steps` tools remain
available. `plan_steps` proposes a dependency-ordered plan without executing it; see
[AGENT_PLANNING_CONTRACT.md](AGENT_PLANNING_CONTRACT.md). A configured repository
adds the executing `run_agent` loop:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run_agent","arguments":{"goal":"Inspect the source and prepare a candidate preview.","maxSteps":8}}}
```

Perform the normal MCP initialization first. Callers may reduce `maxSteps`,
`maxTokens` and `timeoutSeconds`; they cannot exceed operator limits or choose
another repository endpoint. Each call gets independent session state. The existing
stdio transport remains sequential; cancellation is supplied by the process/thread
and configured deadlines, not concurrent in-band notification processing.

## Tests and design provenance

Maven `verify` runs the agent state-machine, loopback HTTP and CLI/MCP contract
suites alongside the existing provider suites. Existing synthetic JNI qualification
also executes a scripted C++ tool/final exchange through the real JNI byte bridge.
That fixture tests wiring, lifetime and protocol behavior; it is not trained-model
inference or physical GPU qualification.

The orchestration design was reviewed against
[ChromeAIAgent at b590a51](https://github.com/guberm/ChromeAIAgent/tree/b590a51891e6bd30bc9b1804914578854147fbb4).
The implementation uses ChromeLLM's existing Java/JNI owners and repository service;
no extension source, provider credentials, DOM automation or challenge solution
code is copied. Canonical recipe execution, category review and exact source receipts
are maintained in the linked Synexia pull request.
