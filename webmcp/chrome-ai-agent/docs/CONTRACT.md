# Chrome AI agent v0 — normative contract

## Scope and authority

- Only the operator-selected extension ID (via native host `allowed_origins`) can call the installed host. Installation, registered executable path, and approved checkouts are explicit operator actions.
- The native host accepts only `hello` or `inventory`, with optional `repo` selected from a fixed allowlist. It never executes commands, changes repositories, reads credentials, downloads files, fetches GitHub resources, or opens an arbitrary pathname from a message.
- The extension uses Chrome's browser-owned built-in AI via `LanguageModel.availability()` / `LanguageModel.create()` on a user click. AI output is display text, not a command or authorization token. There is no remote provider fallback.
- The extension and the standalone ChromeLLM JNI runtime remain independent products; the extension does not extract Chrome-managed model weights.
- No implicit filesystem writes: the native host reads an existing operator-specified property file. It never creates its own cache, config, log, or installation directory.

## Native protocol

- Chrome Native Messaging frame: 4-byte unsigned length in machine byte order, followed by UTF-8 JSON bytes.
- Requests are strict flat JSON string maps (`{"op":"hello"}` or `{"op":"inventory","repo":"ChromeLLM"}`). Duplicate keys, nonstring values, extra keys, malformed UTF-8, malformed JSON and frames larger than 1 MiB are rejected. Responses are bounded to 1 MiB.
- A rejection yields a bounded structured error and no side effect. Standard output is reserved exclusively for framed messages; diagnostics, if ever added, must go to standard error and must not include secrets.
- Root paths come only from a specified, already-existing properties file. Inventory reads only a fixed set of repository-contract Markdown/POM files. It rejects symlinks, path escapes, and files outside the canonical approved root.
- All file hashes are SHA-256 computed from bytes read. Hash values are evidence of sampled contents only; they do not establish a repository's complete tree integrity, functional correctness, successful compilation, or runtime GPU execution.

## Acceptance criteria

- Binary transport respects native endian framing and strict UTF-8, even with malformed input.
- Unknown verbs, repos, untrusted keys, path arguments, symlinks, malformed requests and oversized frames fail closed.
- Normal `hello` and inventory work offline in an approved temporary repository.
- Repeat inventory produces the same digest for the same file bytes.
- Browser/UI tests and Chrome's actual built-in AI availability are separate qualifications, not proved by Java tests.
- Any future GitHub/native provisioning is a separate opt-in, SHA-pinned Maven recipe with its own licensing, cache, offline, ABI, driver and executable-signature gates.

## Chrome origin admission (native launch)

Chrome appends the caller's extension origin to the native executable arguments;
on Windows it may also append a `--parent-window=` handle. The operator's launcher
passes these arguments through verbatim, after the fixed absolute configuration path.
The Java host rejects duplicate, unexpected or malformed launch arguments, then
compares the origin against the exact `agent.allowed_origin` configured by the
operator. Both the manifest's `allowed_origins` and this second check must agree.
The trailing `/` is treated as optional when comparing an origin; wildcard origins
are never accepted. Direct Java invocations with only the configuration path are
reserved for read-only offline integration tests and operator diagnostics.

## Explicit Maven handoff

The extension may copy *only its most recently generated bounded advisory text*
to the clipboard after a distinct user click. Inspection and failed proposals clear
this selection. It does not create candidate files or change Maven sources. The
operator reviews and saves that text in an approved existing location, then may pass
it to the separately installed Smart Maven plugin with `-Dm3.smart.candidateFile=`.
The plugin treats the candidate as untrusted commentary; it cannot become a Maven
command, POM patch, executable or download URL without a separate approval gate.

Chrome manages the built-in model and may download it on explicit user activation.
Neither the extension nor this host reads Chrome-managed model weights. Browser
availability and Windows native-registration compatibility require physical tests.

The native host classes use the product namespace `io.synexia.chromellm.webmcp`; this avoids creating parallel unscoped/default-package types. There is no new dependency in the standalone Java/JNI runtime.

## Packaged Windows launcher admission

A Java `jpackage` launcher with `--arguments` alone cannot be relied on to
prepend an approved config path when Chrome supplies origin arguments. For a
Windows console app-image, the operator must bake an immutable-by-extension
JVM option `-Dchromellm.agent.config=C:/approved/config.properties` into the
launcher with `jpackage --java-options`. The host then interprets the runtime
arguments exclusively as Chrome's origin and optional parent-window handle.
If both a JVM-pinned config and a positional config are provided, the call
fails closed. The JVM property is an operator packaging input, never a native
message field. This is tested with direct Java invocation; Windows jpackage
and HKCU registration remain unverified until run on actual Windows.
