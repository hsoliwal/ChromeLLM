# ChromeLLM historical deletion custody — 2026-11-15

## Frozen scope
- Receiving Git commit: `9746f57901b75245e870163406bcb63d74ba7132` (`main` snapshot). Do not interpret a moving `main` or repository-fleet inventory as having been covered.
- The GitHub REST commit list at that SHA contains **199 reachable commits**, from initial release `ac152dabe636d67761208912e15b7ea7211bfb73` through the frozen receiver. Every one of those **199/199 commit objects** was fetched and its GitHub first-parent changed-file statuses inspected; **0 fetch/parse failures**.
- **44 merge commits** exist; GitHub's ordinary commit file list only describes first-parent differences. Second-parent merge edges and all commits solely reachable from other refs remain **NOT_RUN / HOLD**. This is **not** the full `hsoliwal/*` commit history.
- **247 deletion events** (some repeated across merge commits and descendant replays) in **10 deleting commits**, over **94 distinct historical paths**. All 247 deleted Git blob OIDs were independently compared against those paths in the relevant **first-parent Git trees**: **247/247 exact**; no parent tree was truncated.
- Frozen recipient tree has 266 Git entries; among the 247 deletion events, 237 have no exact blob/path in that receiving tree, 6 have a replaced path, and 4 have an exact blob relocated somewhere else in that same repository. These statuses describe only *this* receiving tree, not loss of historical objects.

## Cross-repository recovery proof
**67 historically removed ChromeLLM files** from the clean-output evacuation (`a11d5989253e977eb55cadf36efd6bfdaccbb707`, merged as ChromeLLM PR #34) are preserved **byte-for-byte** in `hsoliwal/com.synexia` at `forks/ChromeLLM/<old path>`.

For all 67 paths, three independently retrieved identities match: deleting source's first-parent old Git blob SHA, `forks/_evacuation-20261114/ChromeLLM.tsv` ledger Git blob `e3caa0cefad3f3c1018c793805d8da933b5d65df`, and the present destination Git tree SHA `2d6f77cf5fa9987a103a42ee05d7b4a29a5ccfbc`. Result **67/67 exact**, zero missing or mismatching. Source is already preserved; do not duplicate or re-activate it. Source PR #34, destination `hsoliwal/com.synexia#17485`.

The **remaining 27 distinct deleted paths** belong to browserless/ChromeML retirement and historical documentation cleanup across prior merges/commits. One historical documentation blob also exists exactly in the Synexia archive, but the rest require intentional-retirement and legal review before any *additional* archive-copy or active restoration. These paths are retained by immutable Git history; this audit has not established an accidental irreversible loss.

## Recovery non-actions
- ChromeLLM PR #5 (merged September 25): 168 old feature-branch blobs compared with current receiver: 90 exact at path, 59 changed at path, 19 absent (mostly browser-era). This is not evidence that all old media functionality disappeared.
- ChromeLLM PR #50 was **closed unmerged as intentionally superseded** by the canonical `standalone/native/DawnArtifactInstaller.java` and `-Pnative-dawn`. Its historical `DawnArtifactProvisioner.java` and contract test are not activated; a competing downloader would violate the single owner contract.
- Do not reintroduce deleted browser binaries, ChromeML runtime, legacy assets, or retired guides just because a historical path is missing.
- No Java/JNI/Maven runtime source changed by this audit; compiler, JVM/ABI tests, and live device tests are **NOT_RUN**, not inferred from manifest checks.

## Artifacts and verification
- `MAIN_COMMIT_CENSUS.tsv`: every mainline commit SHA with parent count and first-parent diff counters, ordered newest to oldest.
- `DELETED_BLOB_CUSTODY.tsv`: all 247 deletion-edge receipts with deleting commit, first parent, source path and old blob identity, receiving-tree status, cross-repo custody and explicit HOLD.
- Google Drive scratch with detailed fetched records: https://docs.google.com/document/d/1kDlCO8dADoL4NYjiSO7L8i3XonhGfi6ZFCDMH7oW-_A/edit.
- A source-bearing recovery PR may follow **only** after proving an exact historical blob absent from current owners and establishing that removal was unintended. This PR is review-only/DRAFT/HOLD and changes no existing production files.

## Reproducibility constraints
GitHub API `GET /repos/hsoliwal/ChromeLLM/commits?sha=<frozen>&per_page=100&page=1,2` and `GET /commits/<sha>`; for each deleting SHA the first parent tree from `GET /git/trees/<parent>?recursive=1`. Compute current-recipient blob identity from `GET /git/trees/<frozen>?recursive=1`. For the 67 cross-repo paths use `GET /repos/hsoliwal/com.synexia/git/trees/2d6f77cf5fa9987a103a42ee05d7b4a29a5ccfbc?recursive=1`. Treat GitHub `files[].sha` as a candidate old OID until confirmed against its parent tree; that confirmation was performed for all 247 events. Never classify source equivalence by path or filename alone.
