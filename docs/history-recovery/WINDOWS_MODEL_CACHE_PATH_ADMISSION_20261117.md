# Smart Build Windows cache path-admission correction

**DRAFT / HOLD** — frozen source baseline `1131586adf2b47246ca290c706ace11760e9e7cc`. Contract precedes code. Do not merge without Windows Maven verification.

## Defect evidence

The merged `hsoliwal/ChromeLLM` PR #71 at commit `c795d3c20e1bc170144002ace2e46faa0b545f82` received failing GitHub Actions checks, despite a successful merge. Windows `java (windows-latest)` job 132805603371 and Windows `jni-synthetic` job 132805603460 both failed in `fork-smart-build-contract-tests` with the **actual** message `model cache must not traverse symlinks`. Their logs locate the failure in the Maven `SmartBuildContractTestMain` execution. The separate Dawn CMake job 132805602953 failed on a missing DawnConfig.cmake and is explicitly **OUT OF SCOPE**.

The relevant error text is thrown by `SmartBuildMain.prepareModelCache`, not `ModelArtifactCache`. A direct lexical comparison `path.toRealPath().equals(path)` is used for a valid external model-cache directory. On Windows the actual path spelling can differ from the canonical filesystem spelling without a symlink (case and short-name aliases), producing a false security rejection. This is the specific hypothesis to test; GitHub logs alone do not prove a particular Windows alias produced it.

## Locked compatibility and safety contracts

The caller and provider signatures, `SmartBuildMain.run` overloads, recipe schema, cache isolation, target confinement, model selection, error messages, offline behavior, HTTP/JNI provider routing and candidate publication remain unchanged. The patch must modify only private cache-path admission. No external model downloads or network connections are introduced.

A cache location must already have a real directory ancestor; no existing component of the absolute cache path may be a symbolic link or non-directory reparse point. All existing components must be verified **before** `Files.createDirectories(cache)`; the created path must be verified again before provider construction. A file-identity comparison may be used where the platform supplies both no-follow and followed `fileKey` values, rejecting redirecting reparse points without relying on spelling. A harmless case/short-name spelling difference may not itself cause a refusal. Preserve the existing `model cache must not traverse symlinks` diagnostic.

### Tests and stage order

1. Freeze exact pre-change blobs: `SmartBuildMain.java` `8312c0267fbbf80d9b3f5459a99da8808610d4d8`; `SmartBuildContractTestMain.java` `6ac425e3db380fa09bb5db1098d341154665991d`.
2. Document contract, then patch the private path check and add bounded tests: existing symlink-parent refusal, existing online cache preparation and offline behavior, plus an optional Windows-only case-alias fixture that checks both spellings name the same directory before exercising the cache.
3. Lint, compile, existing full Maven `verify`, synthetic JNI and Windows CI, then runtime checks; never declare unexecuted gates passed.
4. Recheck exact postimage blobs, scoped GitHub diff and idempotence. This PR must remain draft until those gates pass. It must not depend on the older 15-file orphan branch, and it must not downgrade ModelArtifactCache's separate validation logic.

## Boundaries

No default-branch write, no merge, no symlink traversal, no hidden file relocation/deletion and no external behavior expansion are authorized. This is one defensive correctness leaf motivated by an observed post-merge failed check, not evidence that the entire repository history has been recovered. Race-free secure filesystem handles would require a separate OS-level protocol, and no such guarantee is asserted here.
