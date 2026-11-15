<!-- SPDX-License-Identifier: MIT -->
<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors -->
# Native smart-build qualification

This note records the Linux, Java 21 and Maven 3.9.11 qualification observed on
2026-11-15. The production Java classes and JNI bridge were compiled and exercised.
Native inference checks used the explicitly synthetic llama test API. They do not
establish real GGUF inference, model quality or physical GPU execution.

## Source identities and ownership

The receiving baseline is ChromeLLM main
[`869ddb4db8e75dfb2633614b2ee164e493a084fa`](https://github.com/hsoliwal/ChromeLLM/commit/869ddb4db8e75dfb2633614b2ee164e493a084fa).
The fork-source pin and Maven inference entry originated in
[PR #44](https://github.com/hsoliwal/ChromeLLM/pull/44), head
`b61a08412364fb29108fa1f5b1daec564f446a7f`. That PR merged into a feature branch;
this update brings its capability into the main-based product additively.

`ForkSourcePin` validates the explicitly supplied native source checkout.
`SmartBuildMain` delegates provider selection to the existing `StandaloneMain`
and `ModelBackend` contracts. Its lazy URL, digest and cache options use
`LazyModelBackend` and `ModelArtifactCache`; there is no additional downloader,
native inference engine or Maven reactor planner. The existing JNI implementation
and native entry points are unchanged.

The lazy lifecycle output already present in the receiving baseline was retained
byte-for-byte. Its principal source identities are:

| Existing owner | SHA-256 |
|---|---|
| `ModelArtifactCache.java` | `b9ec4975a301a9f0235ff07ef7895f1613254a216846db670f154b9db9794467` |
| `LazyModelBackend.java` | `988681234f5376bd202b744dc63e1d0615bebde30b599f0078c50ae810cfce6a` |

Both files are in `standalone/src/main/java/io/synexia/chromellm/standalone/`.
The existing `native-synthetic`, `native-fork` and `native-dawn` profile definitions,
including their validation guards and Dawn prefix configuration, are retained
verbatim. The added `fork-native` profile validates
its own mode; the existing workflow checks all three conflicting native-profile
pairs. `smart-build` remains an explicitly selected inference-candidate operation.

## Executed checks

| Check | Observed result |
|---|---|
| Complete standalone Maven Java suite | 640 assertions: 452 existing, 148 lifecycle assertions in 14 cases, and 40 fork/smart-build assertions |
| Strict standalone Java compilation | 18 production and 11 test compilation units passed `--release 21 -proc:none -Xlint:all -Werror` |
| Existing Java/JNI contracts | Nine original public/protected Java types, including the nested device record, and the generated JNI header retained identical signatures |
| Native synthetic build before the receiving-baseline build changes | Maven verification passed, including the 640 Java assertions and CTest 1/1 |
| Original JNI matrix with `-Xcheck:jni` | 87 normal checks, 58 checks with the synthetic WebGPU provider absent, and one allocation-limit check with a 32 MiB heap |
| Production HTTPS acquisition | 46 loopback TLS checks plus SSL-context restoration; verified cold/warm reuse, response rejection, stalled-body deadline, caller interruption and client/partial-file cleanup |
| Maven `smart-build` integration | The actual lazy provider and JNI bridge returned the expected Unicode synthetic result with matching prompt and output receipts |
| Bounded collision regression | The 40-check smart-build runner passed with a 32 MiB heap while refusing a 256 MiB sparse prior artifact |
| Native source-pin execution | An actual disposable Git checkout passed its exact pin and rejected a wrong pin before CMake |
| Java runtime dependencies | `jdeps` reported only `java.base,java.net.http` |

The HTTPS and lifecycle probes use synthetic bytes. The native fixture implements
the test llama API and never constitutes a licensed model. A source-pin fixture
checks Git admission, not compilation of a complete native donor checkout.

### Latest native Maven profile gate remains pending

The receiving baseline requires Maven Enforcer 3.5.0 for native-profile validation.
That plugin was absent from the qualification environment's cache. An ordinary
dependency-resolution attempt could not reach its configured proxy; a direct
Maven Central attempt then failed DNS resolution. Validation was not skipped and
no plugin substitute was used.

Consequently, the latest native-profile validation and conflicting-profile CI
checks remain **unqualified**. The earlier successful native Maven/CMake and JNI
runs bind to the unchanged JNI and synthetic fixture implementation sources.
The receiving baseline has since changed top-level CMake handling for a staged
Dawn prefix and added a Dawn artifact installer, tests and workflow. The preserved
binary does not qualify those new build paths or the refreshed Maven profile
gate. Complete these gates in an environment that can resolve the declared
dependencies before accepting the native build configuration.

## Reproduction entry points

From the repository root, with Java 21 and the declared Maven plugins available:

```sh
mvn -B -ntp -f standalone/pom.xml verify
mvn -B -ntp -f standalone/pom.xml -Pnative-synthetic verify
```

On Linux, run the existing JNI fixture explicitly:

```sh
java -Xcheck:jni -cp standalone/target/classes:standalone/target/test-classes \
  io.synexia.chromellm.standalone.JniSyntheticContractTestMain \
  standalone/target/native-synthetic/libchromellm_llama_jni.so

CHROMELLM_TEST_NO_WEBGPU=1 java -Xcheck:jni \
  -cp standalone/target/classes:standalone/target/test-classes \
  io.synexia.chromellm.standalone.JniSyntheticContractTestMain \
  standalone/target/native-synthetic/libchromellm_llama_jni.so

java -Xcheck:jni -Xmx32m -cp standalone/target/classes:standalone/target/test-classes \
  io.synexia.chromellm.standalone.JniSyntheticContractTestMain \
  standalone/target/native-synthetic/libchromellm_llama_jni.so oversize-memory
```

Use the platform-specific library and classpath separator on other systems.
[NATIVE_MAVEN_BUILD.md](NATIVE_MAVEN_BUILD.md) describes native build prerequisites;
[FORK_NATIVE_SMART_BUILD.md](FORK_NATIVE_SMART_BUILD.md) describes the pinned fork
and explicit inference recipes. The example is
`standalone/examples/smart-build.example.properties`.

## Remaining runtime qualification

Real fork-source CMake compilation and CPU inference require a compatible native
checkout and an explicitly licensed GGUF; neither was available for these runs.
The receiving baseline's new Dawn artifact download, extraction and package
discovery path was retained without rerunning its dedicated qualification here.
Physical Dawn/WebGPU execution, GPU performance and Windows/macOS native receiving
runs require their own evidence. Chrome-managed model compatibility is not
established by these GGUF-provider tests. Model output remains candidate text and
has no authority to modify source, bypass tests or declare a build successful.
