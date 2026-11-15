// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/** JNI lifecycle/UTF-8 tests against a synthetic llama fixture; NOT a real model test. */
public final class JniSyntheticContractTestMain {
    private static int checks;
    private JniSyntheticContractTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("pass absolute JNI fixture library path");
        Path base = Files.createTempDirectory("chromellm-jni-test-");
        Path model = base.resolve("नमस्ते-require-default-fixture.gguf");
        Path rejected = base.resolve("rejected-model.gguf");
        Path gpuModel = base.resolve("require-webgpu.gguf");
        Path cpuModel = base.resolve("require-cpu.gguf");
        try {
            Files.writeString(model, "synthetic-not-model-weights");
            Files.writeString(rejected, "synthetic-rejected-model");
            Files.writeString(gpuModel, "synthetic-selector-fixture-not-GPU-weights");
            Files.writeString(cpuModel, "synthetic-selector-fixture-not-model-weights");
            Path library = Path.of(args[0]);
            try (LlamaCppJniBackend selected = new LlamaCppJniBackend(library, cpuModel, 1024, 2, 0, "cpu")) {
                check(selected.infer(new ModelRequest("selected", 8, Duration.ofSeconds(1))).text().equals("OK"),
                        "CPU policy passes an explicitly empty GPU device list");
            }
            try (LlamaCppJniBackend automatic = new LlamaCppJniBackend(library, model, 1024, 2, 0, "auto")) {
                check(automatic.infer(new ModelRequest("automatic", 8, Duration.ofSeconds(1))).text().equals("OK"),
                        "auto policy preserves the original native entry point");
            }
            boolean noWebGpu = "1".equals(System.getenv("CHROMELLM_TEST_NO_WEBGPU"));
            // The explicit backend/device contract is written against the full fixture registry.
            if (!noWebGpu) deviceSelection(library, gpuModel, cpuModel);
            if (noWebGpu) {
                try (LlamaCppJniBackend unexpected = new LlamaCppJniBackend(library, gpuModel, 1024, 2, 3, "webgpu")) {
                    throw new AssertionError("missing WebGPU provider silently accepted by " + unexpected.id());
                } catch (IOException expected) {
                    check(expected.getMessage().contains("WebGPU provider is unavailable"),
                            "explicit WebGPU failure identifies the missing provider");
                }
            } else {
                try (LlamaCppJniBackend selected = new LlamaCppJniBackend(library, gpuModel, 1024, 2, 3, "webgpu")) {
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
                check(nativeModel.selectedDevice().isEmpty(), "automatic mode does not invent a selected device");
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
            Files.deleteIfExists(gpuModel);
            Files.deleteIfExists(cpuModel);
            Files.deleteIfExists(base);
        }
        System.out.println("PASS JNI synthetic provider-selection/device-selection/lifecycle/UTF-8/timeout/close: "
                + checks + " checks; no real model or GPU execution");
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

    private static void deviceSelection(Path library, Path gpuModel, Path cpuModel) throws Exception {
        List<LlamaCppJniBackend.Device> devices = LlamaCppJniBackend.availableDevices(library);
        check(devices.size() == 6, "inventory comes from fixture GGML registry");
        check(devices.get(0).backend().equals("CPU") && !devices.get(0).gpu(), "observed CPU classification");
        check(devices.get(1).backend().equals("WebGPU") && devices.get(1).gpu(), "observed WebGPU classification");
        check(devices.get(2).type().equals("IGPU") && devices.get(2).gpu(), "integrated GPU classification");
        check(devices.get(2).description().endsWith("नमस्ते"), "device metadata preserves UTF-8");
        check(devices.get(3).type().equals("ACCEL") && !devices.get(3).gpu(), "accelerator is not reported as GPU");
        try {
            devices.clear();
            throw new AssertionError("native inventory is mutable");
        } catch (UnsupportedOperationException expected) { checks++; }

        LlamaCppJniBackend selected = new LlamaCppJniBackend(library, gpuModel, 1024, 2, 3, "WebGPU", "WebGPU");
        try (selected) {
            check(selected.selectedDevice().orElseThrow().equals(devices.get(1)), "exact selected device observation");
            check(selected.infer(new ModelRequest("first", 8, Duration.ofSeconds(1))).text().equals("OK"),
                    "selected devices and offload settings reach native model load and persist through generation");
            check(selected.infer(new ModelRequest("second", 8, Duration.ofSeconds(1))).text().equals("OK"),
                    "selected device storage survives repeated inference on shared model");
        }
        try {
            selected.selectedDevice();
            throw new AssertionError("closed device observation accepted");
        } catch (IOException expected) { checks++; }
        try (var cpu = new LlamaCppJniBackend(library, cpuModel, 1024, 2, 0, "CPU", "CPU")) {
            check(cpu.selectedDevice().orElseThrow().equals(devices.get(0)), "CPU selection is explicit");
            check(cpu.infer(new ModelRequest("cpu", 8, Duration.ofSeconds(1))).text().equals("OK"),
                    "CPU selection supplies an empty offload-device list");
        }

        expectDeviceFailure(library, gpuModel, 3, "Missing", "GPU0", "not registered");
        expectDeviceFailure(library, gpuModel, 3, "WebGPU", "Missing", "not registered");
        expectDeviceFailure(library, gpuModel, 3, "webgpu", "WebGPU", "not registered");
        expectDeviceFailure(library, gpuModel, 3, "Dawn", "WebGPU", "not registered");
        expectDeviceFailure(library, gpuModel, 0, "WebGPU", "WebGPU", "positive GPU layers");
        expectDeviceFailure(library, gpuModel, 1, "CPU", "CPU", "zero GPU layers");
        expectDeviceFailure(library, gpuModel, 3, "BLAS", "BLAS", "accelerator");
        expectDeviceFailure(library, gpuModel, 3, "Duplicate", "same", "ambiguous");
        for (String invalid : new String[]{"", " ", "WebGPU\0", "WebGPU\n", "WebGPU\t", "WebGPU\u0085",
                "\ud800", "é".repeat(4096)}) {
            try (var unexpected = new LlamaCppJniBackend(library, gpuModel, 1024, 2, 3, invalid, "WebGPU")) {
                throw new AssertionError("malformed device selector accepted: " + unexpected.id());
            } catch (IllegalArgumentException expected) { checks++; }
        }
    }

    private static void expectDeviceFailure(Path library, Path model, int layers, String backend,
                                             String device, String message) throws Exception {
        try (var unexpected = new LlamaCppJniBackend(library, model, 1024, 2, layers, backend, device)) {
            throw new AssertionError("invalid device selection accepted: " + unexpected.id());
        } catch (IOException expected) {
            check(expected.getMessage().contains(message), "selection refusal reason: " + message);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
