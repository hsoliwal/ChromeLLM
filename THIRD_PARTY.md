# Third-Party Files

This repository includes reference files from Chromium and Dawn/WebGPU.

## Chromium

Files:

```text
reference/chrome_ml_api.h
reference/chrome_ml_types.h
reference/on_device_model_executor.cc
```

These files are included for ABI and behavioral reference. They retain their
original Chromium copyright and BSD-style license headers.

## Dawn / WebGPU

Files:

```text
runtime/tag_148_headers/dawn/dawn_proc_table_generated.h
runtime/tag_148_headers/dawn/webgpu_generated.h
```

These files retain their original BSD 3-Clause license headers.

## Not Included

This repository does not include Google model weights, Chrome binaries, or
Chrome proprietary runtime DLLs.



## OpenCV / FFmpeg

The native vision/media integration can link to locally installed OpenCV and FFmpeg libraries.

- OpenCV 4.5 and later: Apache License 2.0.
- FFmpeg: primarily LGPL-2.1-or-later; optional GPL components apply when FFmpeg is built with those components enabled.

No OpenCV or FFmpeg binaries are vendored by this integration.

## Design-Reference Repositories

The following public repositories were inspected for architecture only:

- jvl711/JavaFFmpegLibrary
- tramvm/OpencvJni

No source from those repositories is copied into ChromeLLM. Their inspected repository trees did not expose a repository-level license file, so they are treated strictly as design references.
