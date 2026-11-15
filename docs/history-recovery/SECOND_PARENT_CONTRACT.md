# ChromeLLM second-parent merge-edge recovery audit — contract

## Frozen baseline and scope

This is an additive, non-executable history/custody report. The receiver for the **previous** first-parent audit is the pinned commit `9746f57901b75245e870163406bcb63d74ba7132` (199 GitHub commit objects; 44 two-parent merge commits). Its existing `MAIN_COMMIT_CENSUS.tsv` Git blob is `d90cb09ccfc97b0b04f27c78d2f8682eb2c583ef`.

For each of those 44 merge commits M, fetch `GET /repos/hsoliwal/ChromeLLM/commits/M`, resolve the **second** parent P2, and compare `GET /repos/hsoliwal/ChromeLLM/compare/P2...M`. Preserve GitHub comparison status, ahead/behind, affected-file count, removed path(s) and the SHA of P2. A comparison `removed` path indicates M omitted that path from its second-parent version, **not** necessarily a lost blob or unintended removal. Compare covers the P2-tree-to-merge-result delta; it does not prove no source code was overwritten by different bytes or unchanged-name semantic losses. It also does not enumerate all commits reachable exclusively from every other historical ref.

## Verified results and custody rule

All 44 second-parent comparisons returned `status=ahead` and `behind=0`, none reported a too-large/truncated file list. Exactly **4** comparison records removed a path. Each removal is the **same** `docs/M3_REBUILD_AND_DISTILLATION.md` from old ChromeML rebuild instructions, at merge census indices 95, 104, 108, and 116. For each, the exact Git blob `a8d52790ed0032c8378b50c902665d712b217f4c`, mode `100644`, was independently read from its second-parent Git tree (four non-truncated trees).

That same exact blob already survives in `hsoliwal/com.synexia` at `forks/ChromeLLM/m3/qualification/master-landing-20261028/retained-variants/pr27/docs/M3_REBUILD_AND_DISTILLATION.md` (verified direct file Git blob identity). Classification: **EXACT_ARCHIVED_RETIRED_GUIDE / DO_NOT_REACTIVATE**. The old rebuild guide was explicitly retired as part of the browserless change; no current build path should be changed to reintroduce it.

## Strict boundaries

- All 44 merge commits are from **one frozen mainline commit listing**. This is not a claim that all 60+ discoverable branch histories, historical PR-heads, tags, unreachable refs, or all `hsoliwal/*` Git objects were scanned.
- First-parent deletion audit remains `docs/history-recovery/DELETED_BLOB_CUSTODY.tsv` (247 recorded deletion events, 67 cross-repository exact-source custody matches); do not multiply repeated events into unique lost files.
- No source, JNI, Java, Maven, build, runtime, Git ref, or production behavior is modified by this report. GitHub API returned the compared-tree metadata; hosted Java/native runtime qualification is **NOT_RUN** and is not required to treat this as an inert historical report.
- `SECOND_PARENT_MERGE_CENSUS.tsv` is one row per merge, sorted by mainline census index. A row with no removed paths must use `-`; the count and path must be internally consistent; each M and P2 must be a full 40-hex Git commit ID. Hard fail on unknown/missing parents, changed baseline, truncation, or extra/deleted records. Do not claim no semantic loss from absence of a `removed` status.
- Any proposed *active* reintroduction requires a separate source-bearing PR with proof of accidental loss, exact history blob custody, unchanged external contract, lint→compile→test→runtime results, and prior license approval. **No auto-merge**.

## Scratch / provenance

- Google Drive master scratch: https://docs.google.com/document/d/1xDFSdzx-qVcE3R-apNYaZnb1xv-6W6r8udyNLj4DLdc/edit
- ChromeLLM previous comprehensive first-parent audit: https://docs.google.com/document/d/1kDlCO8dADoL4NYjiSO7L8i3XonhGfi6ZFCDMH7oW-_A/edit
- GitHub source baseline: https://github.com/hsoliwal/ChromeLLM/commit/9746f57901b75245e870163406bcb63d74ba7132
