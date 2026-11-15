<!-- SPDX-License-Identifier: MIT -->
# Build advice with GitHub Actions

The reusable action at `.github/actions/build-ai` invokes the existing Java 21
standalone CLI before or after a project build. It supports the explicitly selected
`chrome`, `jni`, and `cli` providers. It produces suggestions and diagnostics;
the original build command and its exit status remain authoritative. The action
can be called from any repository; the project being reviewed does not need to
use Java or Maven.

Use two explicit workflow steps for the build boundaries. GitHub action lifecycle
`pre` and `post` scripts are job setup/cleanup hooks and do not precisely bracket
an arbitrary Maven command. A Maven lifecycle goal after compilation also cannot
diagnose a compilation failure that stopped that invocation.

## Use across repositories

Keep one implementation in ChromeLLM and pin its reviewed full commit SHA in
each caller. GitHub does not inject pre-build or post-build steps into other
repositories automatically. Each repository chooses its triggers, actual build
commands, and where the two advisory calls belong.

There are two integration forms:

| Existing build arrangement | Integration |
| --- | --- |
| An established job with its own setup actions, services, containers, credentials, or matrix | Add the two composite-action calls around the existing build step. Use `hsoliwal/ChromeLLM/.github/actions/build-ai@REVIEWED_FULL_COMMIT_SHA` in place of the local `uses` paths below. Keep the job's existing build tooling and permissions. |
| A Linux build whose setup and build are reviewed Bash commands | Call the shared [build-with-ai workflow](../.github/workflows/build-with-ai.yml). It runs setup and build together, with optional advice on either side. |

The repository's build system stays explicit. For example, a caller may supply
`./mvnw -B verify`, `./gradlew build`, `npm run build`, `cmake --build build`, or
`python -m build`, with its required setup in `setup-command`. These are examples,
not autodetection rules. Multi-project repositories select the intended working
directory in their own commands. Windows and macOS jobs keep their existing
platform setup; the shared job and this action's qualified shell boundary are
Linux-based.

### Shared workflow caller

Copy [the manual caller example](examples/build-with-ai/workflow.yml) into
`.github/workflows/` in any repository and replace `REVIEWED_FULL_COMMIT_SHA`
with the approved ChromeLLM commit. The example asks for the repository's build
command when dispatched. For automatic CI, keep the repository's existing
events and pass its reviewed command as a literal, for example:

```yaml
name: Build
on:
  push:
  pull_request:
  workflow_dispatch:
permissions:
  contents: read
jobs:
  build:
    uses: hsoliwal/ChromeLLM/.github/workflows/build-with-ai.yml@REVIEWED_FULL_COMMIT_SHA
    with:
      # Replace these with this repository's existing, reviewed commands.
      setup-command: ./ci/setup.sh
      build-command: ./ci/build.sh
      ai-enabled: ${{ vars.CHROME_AI_ENABLED == 'true' }}
      chrome-bin: ${{ vars.CHROME_BIN }}
      chrome-profile: ${{ vars.CHROMELLM_CHROME_PROFILE }}
```

The shared workflow checks out the caller at its triggering commit under
`project/`, and executes its setup/build commands from that directory. Commands
are intentional executable input supplied by the caller workflow; advice and
build logs are never used as commands. Keep commands in reviewed repository
configuration. Avoid replacing them with pull-request titles, issue text, model
output, or other event data.

When advice is enabled, a separate `chromellm/` checkout supplies the action and
standalone engine from the exact commit of the reusable workflow. The engine
JAR is qualified, copied outside the target project, and hashed before advice.
Engine preparation and advisory failures remain visible without replacing the
target build's result. Post-build advice receives the actual build outcome and
log even when the build failed. The shared workflow exposes `build-outcome`,
`ai-preparation-outcome`, `pre-build-status`, and `post-build-status`. Preparation
reports `success`, `failure`, or `disabled`; an advisory call that did not run
reports `skipped`. Reports use seven-day artifact retention.

The shared workflow uses GitHub.com's `job.workflow_repository` and
`job.workflow_sha` context properties to locate its own source. These properties
are not available on GitHub Enterprise Server. On GHES, use the composite-action
form with an explicitly pinned and qualified engine artifact.

### Enable advice for a caller

`ai-enabled` defaults to false. In that mode, the shared workflow runs the actual
setup/build on `ubuntu-latest` and skips engine and browser preparation. Project
toolchain setup remains the caller's responsibility.

Live Chrome advice is admitted only for a `push` or `workflow_dispatch` on the
calling repository's default branch. Pull requests, tags, and other refs still
run the target build with advice skipped. The rule uses the repository's actual
default branch, including names other than `main` or `develop`.

For admitted runs, provide an eligible Linux runner, an installed Chrome
executable, and a dedicated ChromeLLM profile. `ai-runner-labels` is a JSON array
of labels, defaulting to `["self-hosted","linux","chrome-ai"]`; those labels
must be available to the **calling** repository. A reusable workflow does not
automatically share another repository's runner registration. Configure
`CHROME_AI_ENABLED`, `CHROME_BIN`, and `CHROMELLM_CHROME_PROFILE` as repository
variables, or as organization variables where the repository belongs to an
organization and is in their access scope. Do not assume one personal-account
repository's variables apply to other repositories.

`setup-command` is optional. Additional shared-workflow inputs are
`allow-download` (true by default), `ai-timeout-seconds` (180), `max-tokens` (512),
and `max-input-bytes` (65536). Their provider budgets use the same action
validation described below. Cold Chrome preparation may require a larger
explicit deadline. Preserve the profile only on its designated trusted runner.

Adding a caller is separate from qualifying its runner and enabling live
inference. A repository inventory or generated caller example is not evidence
that every repository has been installed, configured, or successfully run.

GitHub's [reusable workflow documentation](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows)
describes cross-repository calls and inputs. Its
[workflow identity context reference](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#example-usage-of-job-context-workflow-identity)
defines the exact source checkout used by the shared workflow.

## Runner and artifact preparation

The action runs on Linux with Bash, Java 21, and GNU coreutils (`sha256sum`,
`timeout`, `tail`, and `mktemp`). It has no Node.js or Python product dependency.
Python is used only by the isolated action contract tests.

Build and qualify a standalone JAR independently before the target build:

```sh
mvn -B -ntp -f standalone/pom.xml verify
cp standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  "$RUNNER_TEMP/chromellm-build-advice.jar"
sha256sum "$RUNNER_TEMP/chromellm-build-advice.jar"
```

Alternatively supply an already qualified artifact and its reviewed SHA-256.
Pass the full digest through `jar-sha256`. Both action invocations must use the
same independent artifact: a failed target compilation or a target `clean` must
not remove the analyser. An engine qualification step is separate from the
project build being analysed.

For `provider: chrome`, install a supported Chrome executable and configure a
dedicated ChromeLLM profile. The Java provider owns the browser and localhost
bridge, profile locking, eligibility checks, model preparation and cleanup. A
successful Chrome process launch alone does not establish model readiness.
`headless` defaults to true; use the mode qualified for the selected runner.

Selecting `allow-download: true` permits lazy Chrome-managed model preparation
on first inference and reuse thereafter. The provider deadline includes that
preparation. Use a larger deadline for a cold model and retain the dedicated
profile for later jobs. `allow-download: false` requires an available Chrome
model and cannot trigger preparation. No personal browser profile should be used.
See [the installed-Chrome provider contract](README.md#installed-chrome-built-in-ai).

For a shared profile, serialize the responsible workflow or use distinct profiles
for independent runners. The existing provider refuses concurrent ownership;
workflow concurrency groups do not serialize jobs across different repositories.
The existing live smoke lane is manually selected, main-only, and uses the
`chrome-ai` self-hosted runner label. Public pull-request execution belongs on
isolated runners rather than that persistent profile runner.

## Pre-build and post-build integration

After checkout, Java setup, and independent engine preparation, add these steps
to the existing build job. The example assumes `engine.outputs.jar`,
`engine.outputs.sha256`, and a UTF-8 packet at `$RUNNER_TEMP/pre-build.txt` were
prepared earlier. The local `uses` path applies when ChromeLLM itself is checked
out. Other repositories can use
`hsoliwal/ChromeLLM/.github/actions/build-ai@<reviewed-full-commit-sha>`.

```yaml
- name: Pre-build suggestions
  id: pre
  if: >-
    ${{ vars.CHROME_AI_ENABLED == 'true' &&
        (github.event_name == 'push' || github.event_name == 'workflow_dispatch') &&
        github.ref == format('refs/heads/{0}', github.event.repository.default_branch) }}
  continue-on-error: true
  timeout-minutes: 6
  uses: ./.github/actions/build-ai
  with:
    phase: pre-build
    jar: ${{ steps.engine.outputs.jar }}
    jar-sha256: ${{ steps.engine.outputs.sha256 }}
    provider: chrome
    chrome-bin: ${{ vars.CHROME_BIN }}
    chrome-profile: ${{ vars.CHROMELLM_CHROME_PROFILE }}
    allow-download: true
    timeout-seconds: 300
    prompt-file: ${{ runner.temp }}/pre-build.txt

- name: Build and verify
  id: build
  shell: bash
  run: |
    set -o pipefail
    ./mvnw -B -ntp verify 2>&1 | tee "$RUNNER_TEMP/build.log"

- name: Post-build diagnosis
  id: post
  if: >-
    ${{ !cancelled() && vars.CHROME_AI_ENABLED == 'true' &&
        (github.event_name == 'push' || github.event_name == 'workflow_dispatch') &&
        github.ref == format('refs/heads/{0}', github.event.repository.default_branch) &&
        (steps.build.outcome == 'success' || steps.build.outcome == 'failure') }}
  continue-on-error: true
  timeout-minutes: 4
  uses: ./.github/actions/build-ai
  with:
    phase: post-build
    jar: ${{ steps.engine.outputs.jar }}
    jar-sha256: ${{ steps.engine.outputs.sha256 }}
    provider: chrome
    chrome-bin: ${{ vars.CHROME_BIN }}
    chrome-profile: ${{ vars.CHROMELLM_CHROME_PROFILE }}
    allow-download: false
    build-outcome: ${{ steps.build.outcome }}
    build-log: ${{ runner.temp }}/build.log
```

Only advisory steps use `continue-on-error`. The build step fails normally, and
the post step can still inspect that failure. It does not run when the build was
skipped or the workflow was cancelled. A successful diagnosis never changes the
build step's failure to success. The manual live smoke workflow additionally
requires completed advice as a provider qualification gate; ordinary advisory
callers do not need that separate smoke-test assertion.

Publish `steps.pre.outputs.report-dir` and `steps.post.outputs.report-dir` with
the workflow's existing artifact-upload step, alongside the real build and test
reports. Use bounded artifact retention. Action failures return a nonzero exit
code and `status: failed`; using `continue-on-error` does not relabel them as
completed advice.

## Inputs and reports

| Input | Behavior |
| --- | --- |
| `phase` | Required `pre-build` or `post-build`. |
| `enabled` | Defaults to true. False records `disabled` without invoking Java or preparing a model. |
| `jar`, `jar-sha256` | Independently prepared Java artifact and its exact SHA-256. Verified before invocation. |
| `provider` | `chrome` by default, or explicit `jni` / `cli`; no automatic fallback. |
| `prompt-file` | Nonempty UTF-8 packet required before build; optional review instructions after build. |
| `build-log`, `build-outcome` | Required after build. Outcome must be the actual `success` or `failure`. |
| `chrome-bin`, `chrome-profile` | Installed executable and dedicated profile. Profile is required for Chrome. |
| `headless`, `allow-download` | Strict booleans, both default true. |
| `max-tokens` | Defaults to 512; provider output admission budget, 1–32768. |
| `timeout-seconds` | Defaults to 180, range 1–3600. Includes provider preparation/inference. |
| `max-input-bytes` | Defaults to 65536, range 1024–1048576, including added instructions. |

The caller owns selection of source facts and test reports. Feed relevant changed
files and deterministic quality facts into bounded packets; model suggestions
do not authorize skipping compilation or tests. Pre-build packets exceeding the
input budget fail explicitly. Post-build logs use the last complete lines within
the remaining byte budget, with an explicit truncation notice. An individual
line larger than that budget can be omitted entirely. Consult the full build log
when earlier diagnostics may be missing. The model's own context limit can be
smaller than the action's byte admission limit.

Each invocation creates a new directory under `RUNNER_TEMP` and exposes these
outputs: `status` (`completed`, `failed`, `disabled`), `report-dir`, `advice-file`
(only for completed output), and `exit-code`. The report contains provider logs,
completed `advice.txt` when available, and `status.properties` with the phase,
build outcome, engine/input/output hashes and truncation flag. The temporary
assembled source packet is removed. Model output is kept in files and is never
emitted as an executable workflow command or interpreted as a shell command.
The existing Java provider owns its streaming response limits; the wrapper also
checks the completed stdout size before admitting it as advice.

The wrapper permits an extra 15 seconds around the provider deadline, then uses
TERM and a further 15-second cleanup allowance before forced termination. Set
the workflow step timeout above that total. Cancellation forwards termination
to the owned process; the Java provider retains its existing cleanup contracts.

## Native providers

The same action can invoke the existing browserless JNI or llama.cpp CLI path:

```yaml
with:
  phase: pre-build
  provider: jni
  jar: ${{ steps.engine.outputs.jar }}
  jar-sha256: ${{ steps.engine.outputs.sha256 }}
  jni-library: /qualified/native/libchromellm_llama_jni.so
  model: /qualified/models/model.gguf
  allow-download: false
  prompt-file: ${{ runner.temp }}/pre-build.txt
```

For `provider: cli`, supply `llama-bin` instead of `jni-library`. To permit lazy
native acquisition, replace `model` with all of `model-url`, `model-sha256`, and
`model-cache-dir`, and set `allow-download: true`. The existing Java cache verifies
the selected licensed artifact. For native offline advice supply the verified
local `model` file; the action refuses a lazy URL when download permission is
false. Native and Chrome-specific options cannot be mixed.

The adapter does not install browsers, native libraries, model licenses, or GPU
drivers. Their existing qualified provisioning owners remain responsible for
those inputs. Provider choice and supplied options are explicit and reported.

## Product tests

```sh
python3 standalone/src/test/scripts/build_ai_action_test.py
mvn -B -ntp -f standalone/pom.xml verify
```

The Python suite executes the real action script with an explicitly fake Java
process. It checks argument boundaries, hashes, outcomes, budgets, provider
selection, cancellation, and refusal paths. It does not establish installed
Chrome model inference or native model quality. The existing Java/provider and
live runtime lanes supply those distinct checks.
