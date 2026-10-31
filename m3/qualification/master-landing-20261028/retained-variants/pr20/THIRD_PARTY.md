# Third-party components and provenance

## Current standalone inference provider

The optional native inference adapter links to `ggml-org/llama.cpp` from an
**operator-supplied** source checkout pinned to commit
`889f2ec96deacbc2d8a9a67dbe181eaa43a2fb3b`. The source and its dependencies
retain their own licenses and notices. No prebuilt llama.cpp runtime or GGUF
model weights are redistributed in this repository.

## Retained independent media dependencies

- OpenCV may be linked for optional image processing and retains the license
  terms of the selected installed version (modern OpenCV uses Apache-2.0).
- FFmpeg may be linked for optional media workloads; check the actual build
  configuration for LGPL/GPL and linked-library obligations.
- OpenCL runtime selection depends on independently installed platform drivers.

## Historical Chromium/ChromeML and Dawn artifacts

Legacy versions tracked Chromium-derived reference headers and large
`optimization_guide_internal.dll` / `webgpu_dawn.dll` binaries despite old
documentation asserting those DLLs were absent. Their presence in Git history
does NOT establish permission to distribute them. The current tree's browser
runtime is removed in the browserless cleanup candidate, but **Git history is
not purged by ordinary file deletion**. License/legal and history remediation
must be reviewed separately; neither these assets nor Google model weights are
needed by the new JNI provider.

See `LICENSE` and `NOTICE`. No automatic download or extraction of proprietary
Chrome weights or runtime binaries is authorized.
