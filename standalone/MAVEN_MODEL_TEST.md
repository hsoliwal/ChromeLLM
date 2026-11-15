<!-- SPDX-License-Identifier: MIT -->

# Maven model download, cache and real JNI verification

Maven can acquire ChromeLLM's published model binary and execute it as part of
`verify`. The `real-model-test` profile downloads the pinned GGUF only on a cache
miss, verifies its full SHA-256 and byte count, then invokes the real CPU JNI
provider in a separate JVM with `-Xcheck:jni`.

The existing `real-model` profile and `dawn-binary` profile remain available for
the native binary smoke checks described in [FORK_NATIVE_SMART_BUILD.md](FORK_NATIVE_SMART_BUILD.md).
The existing five-argument real-GGUF runner still checks SmartBuild receipts,
same-size cache corruption and native lifecycle; `real-model-test` uses its
six-argument entry point for a caller-pinned model, exact size and Maven offline policy.

## Build and test with one Maven invocation

Supply a clean, pinned llama.cpp source checkout, including your existing
`hsoliwal/synexia-llmcpp` fork:

```sh
mvn -B -ntp -f standalone/pom.xml -Pfork-native,real-model-test \
  -Dchromellm.fork.source=/absolute/path/to/synexia-llmcpp \
  -Dchromellm.fork.commit=YOUR_CHECKOUT_FULL_40_HEX_COMMIT verify
```

The `fork-native` profile validates the exact Git revision and clean worktree,
then runs the existing CMake JNI build. The `real-model-test` profile performs
model acquisition and real inference during `integration-test`. Native compile,
download, digest, size and inference failures all fail Maven.

Public GitHub CI acquires its native source using the previously tested public
donor `ggml-org/llama.cpp@889f2ec96deacbc2d8a9a67dbe181eaa43a2fb3b`. It executes the
same Maven profiles and then an offline smart-build recipe. Private fork checkout
continues to use the operator's existing Git access; public CI needs no credential
for another private repository.

## Pinned model and cache settings

| Maven property | Default |
| --- | --- |
| `chromellm.real.model.url` | [ChromeLLM `weights.bin`](https://github.com/hsoliwal/ChromeLLM/releases/download/model-smollm2-135m-q4km-8030f045/weights.bin) |
| `chromellm.real.model.sha256` | `8030f04528538d47bda434f6f0bdf3952c40a58123e4d5e755332f23731a8684` |
| `chromellm.real.model.bytes` | `105454144` |
| `chromellm.real.model.cache` | `${settings.localRepository}/io/synexia/chromellm/models` |
| `chromellm.real.library` | `${project.build.directory}/fork-native/libchromellm_llama_jni.so` |

The model is SmolLM2-135M-Instruct Q4_K_M, an unchanged GGUF distributed with an
Apache-2.0 license and notice in the [model release](https://github.com/hsoliwal/ChromeLLM/releases/tag/model-smollm2-135m-q4km-8030f045).
This small model exercises integration and lifecycle behavior. Passing these
checks does not measure code-repair quality or qualify a different model.

Override the URL, SHA-256 and exact byte count together to test another approved
model. The download follows up to five HTTPS redirects and verifies the final
bytes before opening the model. Staging files are removed on failure. Existing
cache entries are rehashed on every use, and a corrupt entry fails explicitly.
The model stays in the cache and is not packaged into the Java JAR.

The default library path is for Linux. On macOS or Windows, supply the actual
`.dylib` or `.dll` with `-Dchromellm.real.library=...`, and configure the dependent
native library search path for that build. The real-model CI gate runs on Linux;
the existing Java and synthetic JNI gates retain their three-platform matrix.

## Offline reuse

Repeat the invocation with `-o` after the Maven plugins and model are cached:

```sh
mvn -o -B -ntp -f standalone/pom.xml -Pfork-native,real-model-test \
  -Dchromellm.fork.source=/absolute/path/to/synexia-llmcpp \
  -Dchromellm.fork.commit=YOUR_CHECKOUT_FULL_40_HEX_COMMIT verify
```

`${settings.offline}` reaches the model resolver. A missing cache entry fails
before any download. A valid cache is reused even after `mvn clean`, because its
default location is outside `target`. GitHub Actions caches the exact model
digest and still revalidates all restored bytes.

## Use the same model in a smart-build recipe

After the native build and real test, create a properties recipe inside
`standalone`, using the [existing example](examples/smart-build.example.properties).
Set `jniLib` to the built bridge and supply `modelUrl`, `modelSha256` and
`modelCacheDir` matching the table above. Omit the local `model` field when using
the URL fields. A prompt file must be inside `standalone`.

```sh
mvn -o -B -ntp -f standalone/pom.xml -Psmart-build \
  -Dchromellm.smart.recipe=target/my-smart-build.properties verify
```

The smart-build profile honors offline mode, validates the cached model before
provider initialization, and writes its candidate text and receipt under
`standalone/target/smart-build`. It preserves existing source files. The default
Java-only Maven build and the separate synthetic JNI profile remain available.

## What the real gate checks

The test confirms the exact model bytes and GGUF signature, nonempty native
inference, repeatable greedy output for the same prompt, correct prompt hashes,
a second request, repeated close, and rejection after close. A ten-minute Maven
execution timeout bounds the forked test JVM. GitHub also rejects CheckJNI warning
or fatal diagnostics. Dawn/WebGPU device execution remains a separate native
qualification path.
