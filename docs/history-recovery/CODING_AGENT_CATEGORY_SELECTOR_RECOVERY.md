# Historical atom recovery: bounded problem-category selection

Status: **DRAFT / HOLD** pending GitHub CI and review. This is a code-bearing proposal with zero default-branch changes.

## Object custody and narrow intent

Frozen receiving baseline: `43e9d232866f7dfdc6c68811e1a40d6d77a2d78b`. Historical orphan branch `enhance/chromeai-java-jni-category-continuation-20261115`, commit `3bc3c8cbf1827de8e914243f206b901713f451d9` has exactly one commit ahead of the earlier mainline and no prior PR. That commit spans 15 files and has failing Windows checks. Only its **category-filtered `problem_categories` tool atom and focused loopback tests** are in scope. Its other 13 file modifications are **NOT** imported.

Pre-change Git blobs:
- `CodingAgentTools.java`: `365850dd69920def35301dd158fee70868e93853` on frozen main.
- `CodingAgentToolsContractTestMain.java`: `a201c3130ed9cdaf6792818af6e2515679c4af30` on frozen main.

Donor Git blobs:
- `CodingAgentTools.java`: `8a5ecf01aa35e87d60493f01d52424170ce2a2d0`.
- `CodingAgentToolsContractTestMain.java`: `d2abdee13926cafbc6e6fbd0d28635273ecc0368`.

## Locked behavior and additive surface

The registered tool name, executor signature, HTTP method, endpoint, token header, loopback-only enforcement, timeout and bounded response handling stay unchanged. **No-argument invocation** remains `GET /api/problem-categories` with **no query string**. No tool count changes.

Optional query controls on the *existing* `problem_categories` tool are admitted only if bounded and read-only: `platform` in `LEETCODE | HACKERRANK | GEEKSFORGEEKS`, optional `category` or `prefix` (each requires platform), optional uppercase `shape` matching `[A-Z][A-Z0-9_]{0,63}`, and optional integer `limit` from 1 to 64. At most one of category, prefix, and shape may be supplied. Shape may accompany platform. A limit with no selector or platform is invalid. Default limit for any nonempty admitted selector form is 25. Category/prefix bounded to 256 characters, platform to 16, shape to 64; existing text validation rejects malformed Unicode and whitespace-only terms.

Only validated form fields are URL-encoded by the existing GET transport, in stable order: platform, selected qualifier, limit. Unknown parameters, illegal combinations, negative/fractional/oversized limits, invalid shapes, invalid platform and null values must **fail before any network request**.

This change only extends the adapter's existing optional schema; backend support for filtered response content is an independent integration contract and is **NOT presumed**. No source-catalogue entries, donor solutions or external content are copied. The formerly required `source_search` and `source_search_batch` behavior is untouched.

## Verification gates

Order: (1) document this contract; (2) apply minimal two-file code/test patch with source pre-SHAs; (3) lint; (4) compile; (5) run the existing `CodingAgentToolsContractTestMain` loopback fixture and full configured checks; (6) perform runtime integration only if the backend is available. Each gate must be recorded honestly as PASS, FAIL or NOT_RUN. No merge or active production rollout until checks pass and current-base API/behavior review is complete.

Diff invariant: exactly this documentation file, `standalone/src/main/java/io/synexia/chromellm/standalone/CodingAgentTools.java`, and `standalone/src/test/java/io/synexia/chromellm/standalone/CodingAgentToolsContractTestMain.java`; zero other source/build/JNI/workflow changes. Preserve existing files' path and interface signatures.
