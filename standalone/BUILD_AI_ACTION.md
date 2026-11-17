<!-- SPDX-License-Identifier: MIT -->
# Build advice with GitHub Actions

The reusable action at `.github/actions/build-ai` runs the Java 21 standalone CLI
before or after a project build. It supports the explicitly selected native
`cli` (default) and `jni` providers. It produces suggestions and diagnostics, and
the original build command and its exit status remain authoritative. Any
repository can call it; the project being reviewed does not need to use Java or
Maven. The action has no browser code: the former `chrome` provider and the
shared Chrome-runner workflow were removed on 2026-11-17.

Use two explicit workflow steps around the build. GitHub's action lifecycle
`pre` and `post` scripts are job setup and cleanup hooks, so they do not bracket
an arbitrary build command precisely. A Maven goal after compilation also cannot
diagnose a compilation failure that stopped that invocation.

## Use across repositories

Keep one implementation in ChromeLLM and pin its reviewed full commit SHA in each
caller as `hsoliwal/ChromeLLM/.github/actions/build-ai@REVIEWED_FULL_COMMIT_SHA`.
GitHub does not inject steps into other repositories, so each repository chooses
its triggers, its build commands, and where the two advisory calls go.

## Runner and artifact preparation

The action runs on Linux with Bash, Java 21 and GNU coreutils (`sha256sum`,
`timeout`, `tail` and `mktemp`). It has no Node.js or Python product dependency;
Python is used only by the action's contract tests.

Build and qualify a standalone JAR before the target build:

```sh
mvn -B -ntp -f standalone/pom.xml verify
cp standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  "$RUNNER_TEMP/chromellm-build-advice.jar"
sha256sum "$RUNNER_TEMP/chromellm-build-advice.jar"
```

You can instead supply an already qualified artifact and its reviewed SHA-256,
passing the full digest through `jar-sha256`. Both calls must use the same
independent artifact, so a failed compilation or a `clean` of the target cannot
remove the analyser.

## Pre-build and post-build integration

```yaml
- name: Pre-build suggestions
  id: pre
  continue-on-error: true
  timeout-minutes: 6
  uses: hsoliwal/ChromeLLM/.github/actions/build-ai@REVIEWED_FULL_COMMIT_SHA
  with:
    phase: pre-build
    jar: ${{ steps.engine.outputs.jar }}
    jar-sha256: ${{ steps.engine.outputs.sha256 }}
    provider: cli
    llama-bin: /qualified/bin/llama-cli
    model: /qualified/models/model.gguf
    prompt-file: ${{ runner.temp }}/pre-build.txt

- name: Build and verify
  id: build
  shell: bash
  run: |
    set -o pipefail
    ./mvnw -B -ntp verify 2>&1 | tee "$RUNNER_TEMP/build.log"

- name: Post-build diagnosis
  id: post
  if: ${{ !cancelled() && (steps.build.outcome == 'success' || steps.build.outcome == 'failure') }}
  continue-on-error: true
  timeout-minutes: 4
  uses: hsoliwal/ChromeLLM/.github/actions/build-ai@REVIEWED_FULL_COMMIT_SHA
  with:
    phase: post-build
    jar: ${{ steps.engine.outputs.jar }}
    jar-sha256: ${{ steps.engine.outputs.sha256 }}
    provider: cli
    llama-bin: /qualified/bin/llama-cli
    model: /qualified/models/model.gguf
    build-outcome: ${{ steps.build.outcome }}
    build-log: ${{ runner.temp }}/build.log
```

Only the advisory steps use `continue-on-error`. The build step fails normally,
and the post step can still inspect that failure. The post step does not run when
the build was skipped or the workflow was cancelled. A diagnosis never turns the
build step's failure into success. Action failures return a nonzero exit code and
`status: failed`, and `continue-on-error` does not relabel them as completed advice.

For `provider: jni`, supply `jni-library` instead of `llama-bin`. To allow lazy
model acquisition, replace `model` with all of `model-url`, `model-sha256` and
`model-cache-dir`, and keep `allow-download: true`. With `allow-download: false`
the action refuses a lazy URL, so supply a verified local `model` file. The action
installs no native libraries, model licences or GPU drivers.

## Inputs and reports

| Input | Behavior |
| --- | --- |
| `phase` | Required: `pre-build` or `post-build`. |
| `enabled` | Defaults to true. False records `disabled` without invoking Java. |
| `jar`, `jar-sha256` | The independently prepared Java artifact and its exact SHA-256, verified before invocation. |
| `provider` | `cli` by default, or `jni`. No automatic fallback; any other value is refused. |
| `llama-bin` / `jni-library` | The llama.cpp executable (cli) or the JNI library (jni). |
| `model`, or `model-url` + `model-sha256` + `model-cache-dir` | A local model file, or a lazily acquired, hash-verified one. |
| `prompt-file` | A nonempty UTF-8 packet, required before the build; optional review instructions after it. |
| `build-log`, `build-outcome` | Required after the build. The outcome must be the actual `success` or `failure`. |
| `allow-download` | Strict boolean, default true. It gates lazy model acquisition. |
| `max-tokens` | Defaults to 512 (range 1–32768). |
| `timeout-seconds` | Defaults to 180 (range 1–3600), including model acquisition and inference. |
| `max-input-bytes` | Defaults to 65536 (range 1024–1048576), including the added instructions. |

The caller selects the source facts and test reports it sends. Model suggestions
do not authorise skipping compilation or tests. A pre-build packet that exceeds
the input budget fails explicitly. A post-build log contributes its last complete
lines that fit the remaining byte budget, with a truncation notice.

Each invocation creates a new directory under `RUNNER_TEMP` and has four outputs:

- `status`: `completed`, `failed` or `disabled`;
- `report-dir`;
- `advice-file`, set only for completed advice;
- `exit-code`.

The report directory holds:

- the provider logs;
- `advice.txt` when advice completed;
- `status.properties`, with the phase, the build outcome, the engine, input and output hashes, and the truncation flag.

The action never emits model output as a workflow command, and never runs it as a
shell command. It allows 15 seconds beyond the provider deadline, sends TERM, and
forces termination after a further 15 seconds.

## Product tests

```sh
python3 standalone/src/test/scripts/build_ai_action_test.py
mvn -B -ntp -f standalone/pom.xml verify
```

The Python suite runs the real action script with an explicitly fake Java process.
It checks:

- argument boundaries;
- hashes, outcomes and budgets;
- provider selection, including refusal of the removed `chrome` provider;
- cancellation and the other refusal paths.
