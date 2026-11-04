# PR #21 historical source preservation

## Scope and frozen inputs

- Original PR #21 head: `00b219a870a8a3a7c13aef9f1206df1c2db1c1d7`.
- Receiving main: `ca13b385acf7909a5825babdfba19aa4893d05e2`.
- Receiving tree: `7abf5a0b06dfd469ccec422c98ae677755f72ce3`.

## Preservation decision

The complete receiving tree contains 234 files. The original PR head contains 203 files: 196 already match the receiving tree by Git blob and file mode; seven historical retained-variant images differ. Preserve all 234 receiving files unchanged and copy those seven original images verbatim into this source-addressed archive. SOURCE_MAP.tsv records each original path, exact source commit, Git blob, mode, SHA-256 and receiving path.

The seven collisions are all beneath the existing inactive retained-variants/pr20 prefix. Neither variant is declared behaviorally equivalent to the other. Current versions remain at their existing paths; the seven older versions receive explicit archive paths. No file is silently discarded, moved, activated or substituted into a live build.

This follows the existing source-completeness-20261104/RETENTION_DECISION.json pattern. Root and standalone Maven/CMake compilation use src, native and standalone inputs; this archive is outside those active source roots. The nested .github file is historical data, not an installed workflow. Original licenses, attribution and source bytes are retained.

## Verification boundary

Before updating the PR branch, require all 234 receiving path/blob/mode identities to remain exact, all 203 original PR-head identities to resolve either in place or through SOURCE_MAP.tsv, absent archive destinations, deterministic fixed-point replay, and a current-main comparison containing only the nine archive/documentation additions. Preserve both parent histories with a normal merge and guard the PR ref update against a moved head.

This change consolidates historical source and ancestry. It introduces no active Java/JNI implementation, no Maven/OpenRewrite recipe, no model weights and no binary. It does not activate the held PR #20 backend or claim new model inference, platform qualification, runtime parity or performance evidence. Existing applicable CI remains responsible for active target verification.
