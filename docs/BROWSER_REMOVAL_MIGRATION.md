# Browser extraction and JNI migration receipt

- Source: `hsoliwal/ChromeLLM` main commit 17a27eaeee83cc0bb7004d5191aca890a5c1d4f4.
- Canonical source-change recipes: private `hsoliwal/com.synexia`; product receives resulting source, product tests and reviewable diffs.
- Frozen port: `ModelBackend`, `ModelRequest`, `ModelResponse`, and the existing process CLI backend.
- New native port: `LlamaCppJniBackend`, a JNI library using licensed, independently built llama.cpp and a legal GGUF file.

## Removed browser execution owners

The legacy ChromeML runner, Node HTTP server, Windows PowerShell launchers, ChromeML/Dawn DLLs, generated Dawn headers/procs, Chromium ABI references, Chrome-managed model placeholder and Java ChromeAiConditioningClient are removed from the source tree, not from Git history. The diffusion CLI now takes an optional explicit 16-number `--condition-vector` rather than a Chrome-backed prompt or URL; its former `--prompt` and `--chrome-base` flags are unsupported.

## Additional browser-only documentation retirement

Three obsolete launch/rebuild documents were removed from the current product tree: `docs/OPENAI_COMPAT_API.md`, `docs/M3_REBUILD_AND_DISTILLATION.md` and `关键数据.md`. The active Chinese README and the active Java/native build guides were updated to describe standalone JNI. Earlier architecture/audit documentation carries a historical-snapshot warning rather than suggesting obsolete commands. The Synexia canonical deletion manifest must pin these three additional original Git blobs before receiver promotion.

## Verification

- Java closure: `mvn -f standalone/pom.xml verify` and `jdeps` = java.base.
- Test-only native C++: synthetic JNI handshake, Unicode receipt, timeout, close, no browser assets.
- Still pending: licensed real GGUF model inference, ABI/version qualification, CPU/GPU measurement, Windows/macOS coverage, production packaging.
- Chrome-related documentation elsewhere in the repository reflects historical source snapshots and must not be treated as current launch instructions.
