# Browser extraction and JNI migration receipt

- Source: `hsoliwal/ChromeLLM` main commit 17a27eaeee83cc0bb7004d5191aca890a5c1d4f4.
- Canonical source-change recipes: private `hsoliwal/com.synexia`; product receives resulting source, product tests and reviewable diffs.
- Frozen port: `ModelBackend`, `ModelRequest`, `ModelResponse`, and the existing process CLI backend.
- New native port: `LlamaCppJniBackend`, a JNI library using licensed, independently built llama.cpp and a legal GGUF file.

## Removed browser execution owners

The legacy ChromeML runner, Node HTTP server, Windows PowerShell launchers, ChromeML/Dawn DLLs, generated Dawn headers/procs, Chromium ABI references, Chrome-managed model placeholder and Java ChromeAiConditioningClient are removed from the source tree, not from Git history. The diffusion CLI now takes an optional explicit 16-number `--condition-vector` rather than a Chrome-backed prompt or URL; its former `--prompt` and `--chrome-base` flags are unsupported.

## Verification

- Java closure: `mvn -f standalone/pom.xml verify` and `jdeps` = java.base.
- Test-only native C++: synthetic JNI handshake, Unicode receipt, timeout, close, no browser assets.
- Still pending: licensed real GGUF model inference, ABI/version qualification, CPU/GPU measurement, Windows/macOS coverage, production packaging.
- Chrome-related documentation elsewhere in the repository reflects historical source snapshots and must not be treated as current launch instructions.
