# ChromeLLM agent instructions

## Clean output rule (owner directive, 2026-11-14) - read first

This rule overrides any older text in this file.

- This fork is clean output: one coherent ChromeLLM repository. M3 work lives
  inside the project's own classes and packages, written the way the project
  writes its code. It is not stamped across folders.
- Do not add `m3/`, `.m3/`, `docs/m3/` or `tools/m3*` folders.
- Do not add any of the following. They belong in `hsoliwal/com.synexia`, the
  convergence workspace:
  - qualification bundles;
  - evidence, receipts and benchmark tables;
  - work orders;
  - donor copies;
  - recipe sources or recipe resources.
- Do not add parallel `M3`-prefixed types or `*.m3` packages where an existing
  class owns the behaviour. Change that class in place, keep it internal unless
  the owner admits new API, and follow the project's naming.
- A pull request here contains only the product change, plus its tests in the
  project's own test layout. Put the proof material in the paired `com.synexia`
  pull request and link to it.
- This fork's merged pull-request history shows the intended direction.




## Superset merge and history-audit rule (2026-11-18)

Read [.github/MERGE_RULE.md](.github/MERGE_RULE.md) before changing an existing implementation or integrating any branch or PR.
Preserve the supported additive API/behavior superset and select implementations by correctness, predictability, measured performance, then elegance.
Audit all reachable history back to every root, all branches/tags and all PR states for lost code or regressions; preserve append-only history.
Record actual UTC coverage and remaining work in [.github/HISTORY_AUDIT.tsv](.github/HISTORY_AUDIT.tsv); policy installation is not a completed source-history audit.
Prefer verified Apache-2.0 sources and independent implementations while preserving all existing third-party rights; LLM rewriting does not remove license obligations.
Detailed private recipe/proof records remain in canonical Synexia; the compact local policy and checkpoint make the rule discoverable here.
<!-- MERGE-SUPERSET-HISTORY-20261118-2 -->

## MOP SDK: known patterns and explicit machine residue (2026-11-18)

Before source changes, retrieve the [Synexia semantic atom/pattern law](https://github.com/hsoliwal/com.synexia/blob/develop/docs/M3-SCALE/invariants/SYNEXIA-UNIVERSAL-API-ATOM-PATTERN-EVOLUTION-1.md) and [MOP-13 invariant](https://github.com/hsoliwal/com.synexia/blob/develop/docs/M3-SCALE/invariants/SYNEXIA-MOP-SDK-BIDIRECTIONAL-LATTICE-1.md). Descend repository -> project/module -> package -> file -> declarations -> meaningful behavioral atoms, sealing interfaces, effects, ordering, concurrency, ownership and observable contracts. Recognize algorithms and patterns only with versioned catalogue IDs, roles, applicability and evidence. For unmatched structures preserve the source and record `UNKNOWN_ALGORITHM`, `UNKNOWN_PATTERN` or `UNPROVEN_MATCH` in canonical Synexia machine-readable inventory, with source-bound documentation/validated annotation links, proof status and review owner. Never invent uncompilable annotations, force unsound extraction or infer equivalence from names, hashes or an LLM. Recompose qualified atoms upward through the actual caller/dependency closure. Prepare independent files in bounded parallel, then verify and promote serially through source-sealed Synexia recipes and target-native gates. Preserve earlier APIs, history, licenses and notices; this product receives only qualified postimages, not private recipe engines. This policy is not evidence that full-history, atomization, compile/test/runtime or pattern recognition was executed.
