// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** JNI lifecycle/UTF-8 tests against a synthetic llama fixture; NOT a real model test. */
public final class JniSyntheticContractTestMain {
    private static int checks;
    private JniSyntheticContractTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("pass absolute JNI fixture library path");
        Path base = Files.createTempDirectory("chromellm-jni-test-");
        Path model = base.resolve("नमस्ते-fixture.gguf");
        Path rejected = base.resolve("rejected-model.gguf");
        Path cpu = base.resolve("require-cpu-fixture.gguf");
        Path webgpu = base.resolve("require-webgpu-fixture.gguf");
        try {
            Files.writeString(model, "synthetic-not-model-weights");
            Files.writeString(rejected, "synthetic-rejected-model");
            Files.writeString(cpu, "synthetic-cpu-selection-fixture");
            Files.writeString(webgpu, "synthetic-webgpu-selection-fixture");
            Path library = Path.of(args[0]);
            try (LlamaCppJniBackend selected = new LlamaCppJniBackend(library, cpu, 1024, 2, 0, "cpu")) {
                check(selected.infer(new ModelRequest("selected", 8, Duration.ofSeconds(1))).text().equals("OK"),
                        "CPU policy passes an explicitly empty GPU device list");
            }
            try (LlamaCppJniBackend automatic = new LlamaCppJniBackend(library, model, 1024, 2, 0, "auto")) {
                check(automatic.infer(new ModelRequest("automatic", 8, Duration.ofSeconds(1))).text().equals("OK"),
                        "auto policy preserves the original native entry point");
            }
            if ("1".equals(System.getenv("CHROMELLM_TEST_NO_WEBGPU"))) {
                try (LlamaCppJniBackend unexpected = new LlamaCppJniBackend(library, webgpu, 1024, 2, 1, "webgpu")) {
                    throw new AssertionError("missing WebGPU provider silently accepted by " + unexpected.id());
                } catch (IOException expected) {
                    check(expected.getMessage().contains("WebGPU provider is unavailable"),
                            "explicit WebGPU failure identifies the missing provider");
                }
            } else {
                try (LlamaCppJniBackend selected = new LlamaCppJniBackend(library, webgpu, 1024, 2, 1, "webgpu")) {
                    check(selected.infer(new ModelRequest("selected", 8, Duration.ofSeconds(1))).text().equals("OK"),
                            "WebGPU policy passes only WebGPU devices and positive GPU layers");
                }
            }
            expectInvalidCompute(library, model, "webgpu", 0);
            expectInvalidCompute(library, model, "cpu", 1);
            expectInvalidCompute(library, model, "unknown", 1);
            Method nativeOpen = LlamaCppJniBackend.class.getDeclaredMethod("nOpenWithBackend",
                    byte[].class, int.class, int.class, int.class, int.class);
            nativeOpen.setAccessible(true);
            expectNativeComputeFailure(nativeOpen, model, 1, 1); // CPU with GPU layers
            expectNativeComputeFailure(nativeOpen, model, 0, 2); // WebGPU without GPU layers
            expectNativeComputeFailure(nativeOpen, model, 0, 3); // invalid native selector
            try {
                new LlamaCppJniBackend(Path.of(args[0]), rejected, 1024, 2, 0);
                throw new AssertionError("native model-load failure was accepted");
            } catch (IOException expected) {
                checks++;
            }
            LlamaCppJniBackend nativeModel = new LlamaCppJniBackend(Path.of(args[0]), model, 1024, 2, 0);
            try (nativeModel) {
                ModelResponse result = nativeModel.infer(new ModelRequest("Unicode: नमस्ते 🦙", 64,
                        Duration.ofSeconds(10)));
                check(result.text().equals("नमस्ते 🦙"), "UTF-8 request and response");
                check(result.backendId().equals("llama.cpp-jni"), "provider id");
                check(result.promptSha256().matches("[0-9a-f]{64}"), "receipt");
                check(!result.promptSha256().equals(nativeModel.infer(new ModelRequest("other", 8,
                        Duration.ofSeconds(1))).promptSha256()), "distinct prompt receipts");
                try {
                    nativeModel.infer(new ModelRequest("invalid-utf8", 8, Duration.ofSeconds(1)));
                    throw new AssertionError("malformed native UTF-8 was accepted");
                } catch (IOException expected) {
                    checks++;
                }
                try {
                    nativeModel.infer(new ModelRequest("blank-output", 8, Duration.ofSeconds(1)));
                    throw new AssertionError("blank native output was accepted");
                } catch (IOException expected) {
                    checks++;
                }
                try {
                    nativeModel.infer(new ModelRequest("short", 8, Duration.ofMillis(1)));
                    throw new AssertionError("native timeout was not raised");
                } catch (TimeoutException expected) {
                    checks++;
                }
                Thread.currentThread().interrupt();
                try {
                    nativeModel.infer(new ModelRequest("short", 8, Duration.ofSeconds(1)));
                    throw new AssertionError("interrupted caller was accepted");
                } catch (InterruptedException expected) {
                    checks++;
                } finally {
                    Thread.interrupted(); // restore test execution
                }
            }
            nativeModel.close(); // idempotent close
            try {
                nativeModel.infer(new ModelRequest("closed", 8, Duration.ofSeconds(1)));
                throw new AssertionError("closed session was accepted");
            } catch (IOException expected) {
                checks++;
            }
        } finally {
            Files.deleteIfExists(model);
            Files.deleteIfExists(rejected);
            Files.deleteIfExists(cpu);
            Files.deleteIfExists(webgpu);
            Files.deleteIfExists(base);
        }
        System.out.println("PASS JNI synthetic provider-selection/lifecycle/UTF-8/timeout/close: " + checks + " checks");
    }

    private static void expectInvalidCompute(Path library, Path model, String backend, int gpuLayers)
            throws IOException {
        try (LlamaCppJniBackend unexpected = new LlamaCppJniBackend(library, model, 1024, 2, gpuLayers, backend)) {
            throw new AssertionError("invalid compute selection accepted by " + unexpected.id() + ": " + backend + "/" + gpuLayers);
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void expectNativeComputeFailure(Method open, Path model, int gpuLayers, int backend)
            throws Exception {
        try {
            open.invoke(null, model.toString().getBytes(StandardCharsets.UTF_8), 1024, 2, gpuLayers, backend);
            throw new AssertionError("native compute selection bypassed validation");
        } catch (InvocationTargetException wrapped) {
            check(wrapped.getCause() instanceof IOException, "native boundary rejects invalid compute selection");
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
