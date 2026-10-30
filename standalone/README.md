# ChromeLLM standalone inference core (Java 21)

**Chrome is an optional adapter, not the owner of the inference contract.** This
separately buildable JPMS module provides a browserless text-inference seam and a
working `llama.cpp` CLI adapter. It does not import Chrome/Chromium, need Chrome,
use Node/PowerShell, or depend on a third-party Java library. The module requires
only `java.base`. It does **not** embed an inference model: the `llama-cli`
executable and a compatible, lawfully supplied GGUF model must be installed by
the operator. It neither extracts nor redistributes Chrome/Gemini Nano models.

The existing ChromeML runner and Chrome built-in AI APIs remain independent
integration paths. A browser-free ChromeML subprocess still depends on ChromeML
binaries; it is not the same achievement as independence from Google's runtime.

## Build and invoke

```sh
mvn -f standalone/pom.xml verify
jdeps --print-module-deps standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar

java --module-path standalone/target/chromellm-standalone-0.1.0-SNAPSHOT.jar \
  --module io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain \
  --llama-bin /path/to/llama-cli --model /path/to/licensed-model.gguf \
  --prompt-file /path/to/request.txt --max-tokens 256 --timeout-seconds 120
```

One invocation sends exactly one prompt to exactly one selected backend; file
iteration, one-call-per-source-file enforcement, validation and promotion belong
to the Synexia recipe pipeline. Output is *unverified model-generated candidate
text*, not a source-of-truth replacement or a success claim for generated Java.

The adapter uses an argv array (never a shell) and a temporary UTF-8 prompt file
instead of exposing input text in process arguments. It captures stdout/stderr
separately, bounds stdout, enforces the requested deadline around the child
process, refuses nonzero exit and empty output, and deletes temporary files.
Interruption destroys the direct child; full descendant process-tree containment
is not established. Temp-file confidentiality depends on the host filesystem and
user account. The prompt SHA-256 receipt binds the input, **not** model accuracy.

The CLI flags are `-m`, `-f`, `-n`, `--no-display-prompt`, `--no-show-timings`,
`--simple-io`, `--single-turn`; compare your installed llama.cpp version to
[its CLI documentation](https://github.com/ggml-org/llama.cpp/blob/master/tools/cli/README.md).
No real model or GPU execution has been qualified in this first slice. The
synthetic subprocess contract test proves dispatch, error propagation, bounded
waiting and cleanup only. Never treat a successful CLI exit as proof of semantic
correctness; apply the normal compile/lint/test/diff acceptance gates.

## Ownership and next slices

1. **Core:** `ModelRequest`, `ModelResponse`, `ModelBackend`; no browser import.
2. **First browserless provider:** `LlamaCppCliBackend`, externally supplied
   executable and licensed model, independent of ChromeML binaries.
3. **Compatibility adapters:** Chrome Prompt/Summarizer/Chrome2api stay outside
   this module and must implement the same request/receipt boundary when migrated.
4. **Future core:** per-session cancellation, streaming, model loading, strict
   structured outputs, OpenAI-compatible API, stable JNI/C ABI, CPU/GPU dispatch,
   comparative model tests and platform qualification. These are **not** claimed
   implemented here.

The canonical Synexia recipe workspace governs any recurring source-convergence
or interface-generation transformations. Generated results may be transferred to
M3JDK21 only after source, license, contract and receiver qualification.
