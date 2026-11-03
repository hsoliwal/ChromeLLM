<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 ChromeLLM reproducible rebuild boundary

This Chrome2api-based fork provides **source** for `runtime/ChromeMLBareRunner.cpp`
and a platform-specific Zig/Windows build path. This wrapper adds a non-mutating
source inventory and an explicit rebuild to `target/m3-chrome-rebuild/`, preserving
the existing prebuilt `runtime/ChromeMLBareRunner.exe` and upstream compatibility.

Dry run (no compiler, runtime or model access):

```
python scripts/m3_rebuild_runner.py
```

Build (requires Windows-targeting Zig toolchain, runtime-specific headers and a
compatible Windows host for subsequent smoke testing):

```
python scripts/m3_rebuild_runner.py --build
```

The receipt hashes the C++ source, output binary and Zig version, without
including or reading `weights.bin`, browser cookies, proprietary DLL contents,
prompts, or any teacher/student training data. The binary is *not* automatically
run or distributed. Source compilation cannot convert Google-owned opaque
Chrome model weights to GGUF, safetensors or a licensed model architecture.

Canonical **recipe-first Java/JNI distillation arithmetic**, model provenance,
and optional JMOD receiving are owned by `hsoliwal/com.synexia` PR #11348,
`M3ModelDistillV1`, rather than another Chrome-specific training engine.
An independently published Apache-2.0 open-weight model such as Qwen3-4B is
an eligible separate candidate only after pinning its complete tokenizer,
configuration, checkpoint, dataset rights and evaluation method. Full 4 GB
weight redistillation is **not completed or claimed** by this rebuild script.

Open gates: Zig cross-build on Windows; source/prebuilt behavior parity; browser
Prompt API compatibility; runtime licensing; student checkpoint training and
held-out quality measurements; JNI/Tornado GPU oracle receipts.
