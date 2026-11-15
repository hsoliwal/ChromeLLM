# Third-party provenance

The fork's Chrome2api-derived work retains its original MIT LICENSE and copyright attribution/history. The former Chromium reference sources, Dawn generated headers, Windows ChromeML runner, DLLs and related executables have been removed from the current product tree. Git history remains intact; historical third-party rights and notices are not erased.

Independent text inference now links to a separately installed `hsoliwal/synexia-llmcpp` llama.cpp C API (observed revision 650ac3739d993e1d425f2d5db3c4205b3606cc30). No external llama.cpp source, engine binary, GGUF model or tokenizer is vendored here. The test-only llama.h is a first-party synthetic subset, not an upstream header. Operators must review licenses, version pins, ABI and all native transitive dependencies before packaging.

OpenCV, FFmpeg and OpenCL integrations for the unrelated image/video pipeline require separate build-specific license review. FFmpeg licensing can vary by compile options.

The optional installed-Chrome AI provider adds first-party Synexia Java/CDP code and browser lifecycle adaptations from Google's Apache-2.0-licensed [web-ai-demos](https://github.com/GoogleChromeLabs/web-ai-demos/tree/2edcaedca4a13913598b6365d3c41b0f96e8d09d) project. See [standalone/NOTICE.md](standalone/NOTICE.md) for the retained Google attribution, modified source scope and provenance, and [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt) for the license. Existing MIT source notices remain unchanged. Chrome, its model assets and third-party CDP client libraries are not bundled.
