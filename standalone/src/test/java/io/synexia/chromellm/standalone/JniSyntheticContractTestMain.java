// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** JNI lifecycle/UTF-8 tests against a synthetic llama fixture; NOT a real model test. */
public final class JniSyntheticContractTestMain {
    private static int checks;
    private JniSyntheticContractTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("pass JNI fixture library path and optional regression name");
        Path base = Files.createTempDirectory("chromellm-jni-test-");
        Path model = base.resolve("नमस्ते-require-default-fixture.gguf");
        Path rejected = base.resolve("rejected-model.gguf");
        Path gpuModel = base.resolve("require-webgpu.gguf");
        Path cpuModel = base.resolve("require-cpu.gguf");
        Throwable failure = null;
        try {
            Files.writeString(model, "synthetic-not-model-weights");
            Files.writeString(rejected, "synthetic-rejected-model");
            Files.writeString(gpuModel, "synthetic-selector-fixture-not-GPU-weights");
            Files.writeString(cpuModel, "synthetic-selector-fixture-not-model-weights");
            Path library = Path.of(args[0]);
            if (args.length == 2) {
                focusedRegression(args[1], library, model, base);
                System.out.println("PASS JNI focused " + args[1] + ": " + checks + " checks");
                return;
            }
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
                inputBoundaries(nativeModel);
                pieceBoundaries(nativeModel);
            }
            nativeModel.close(); // idempotent close
            try {
                nativeModel.infer(new ModelRequest("closed", 8, Duration.ofSeconds(1)));
                throw new AssertionError("closed session was accepted");
            } catch (IOException expected) {
                checks++;
            }
            rejectMissingVocabulary(library, base);
            for (String kind : List.of("platform", "foreign", "override", "virtual", "virtual-foreign")) {
                interruptInFlight(library, base, kind);
            }
            nativeLockDeadline(library, base);
            independentClose(library, base);
            JniAgentContractTestMain.main(new String[] {library.toString()});
        } catch (Exception | Error primary) {
            failure = primary;
            throw primary;
        } finally {
            try { deleteFixtureTree(base); }
            catch (IOException cleanup) {
                if (failure == null) throw cleanup;
                failure.addSuppressed(cleanup);
            }
        }
        System.out.println("PASS JNI synthetic provider-selection/device-selection/lifecycle/UTF-8/timeout/close: "
                + checks + " checks; no real model or GPU execution");
    }

    private static void focusedRegression(String name, Path library, Path model, Path base) throws Exception {
        switch (name) {
            case "utf16", "oversize-memory", "piece-size", "piece-retry" -> {
                try (var backend = new LlamaCppJniBackend(library, model, 1024, 2, 0)) {
                    switch (name) {
                        case "utf16" -> expectInputFailure(backend, "bad\ud800text");
                        case "oversize-memory" -> expectInputFailure(backend, "x".repeat(16 * 1024 * 1024 + 1));
                        case "piece-size" -> expectPieceFailure(backend, "oversized-piece");
                        case "piece-retry" -> expectPieceFailure(backend, "oversized-retry-piece");
                        default -> throw new AssertionError(name);
                    }
                }
            }
            case "vocabulary" -> rejectMissingVocabulary(library, base);
            case "native-lock" -> nativeLockDeadline(library, base);
            case "close-isolation" -> independentClose(library, base);
            case "platform", "foreign", "override", "virtual", "virtual-foreign" -> interruptInFlight(library, base, name);
            default -> throw new IllegalArgumentException("unknown JNI regression name");
        }
    }

    private static void inputBoundaries(LlamaCppJniBackend backend) throws Exception {
        for (String invalid : List.of("a\ud800b", "a\udc00b", "\ud800\ud800", "a\0b")) {
            expectInputFailure(backend, invalid);
        }
        expectInputFailure(backend, "x".repeat(16 * 1024 * 1024 + 1));
        expectInputFailure(backend, "€".repeat(16 * 1024 * 1024 / 3 + 1));
        check(backend.infer(new ModelRequest("x".repeat(16 * 1024 * 1024), 8,
                Duration.ofSeconds(10))).text().equals("OK"), "exact 16 MiB input is accepted");
        check(backend.infer(new ModelRequest("valid supplementary 🦙", 8,
                Duration.ofSeconds(1))).text().equals("OK"), "supplementary UTF-16 remains accepted");
    }

    private static void expectInputFailure(LlamaCppJniBackend backend, String prompt) throws Exception {
        try {
            backend.infer(new ModelRequest(prompt, 8, Duration.ofSeconds(10)));
            throw new AssertionError("malformed or oversized native prompt accepted");
        } catch (IOException expected) {
            checks++;
        }
    }

    private static void pieceBoundaries(LlamaCppJniBackend backend) throws Exception {
        expectPieceFailure(backend, "oversized-piece");
        expectPieceFailure(backend, "oversized-retry-piece");
        expectPieceFailure(backend, "minimum-piece-size");
        check(backend.infer(new ModelRequest("large-valid-piece", 8, Duration.ofSeconds(1)))
                .text().equals("A".repeat(600)), "a correctly sized large token piece is preserved");
    }

    private static void expectPieceFailure(LlamaCppJniBackend backend, String prompt) throws Exception {
        try {
            backend.infer(new ModelRequest(prompt, 8, Duration.ofSeconds(1)));
            throw new AssertionError("out-of-bounds token piece accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("token piece exceeds"), "token size was not rejected at the native boundary");
        }
    }

    private static void rejectMissingVocabulary(Path library, Path base) throws Exception {
        Path model = base.resolve("null-vocabulary.gguf");
        Files.writeString(model, "synthetic invalid vocabulary");
        try (var unexpected = new LlamaCppJniBackend(library, model, 1024, 2, 0)) {
            throw new AssertionError("missing vocabulary accepted by " + unexpected.id());
        } catch (IOException expected) {
            check(expected.getMessage().contains("no vocabulary"), "missing vocabulary rejection");
        }
    }

    private static void interruptInFlight(Path library, Path base, String kind) throws Exception {
        Path model = base.resolve("interrupt-" + kind + ".gguf");
        Path ready = Path.of(model + ".ready");
        Files.writeString(model, "synthetic interrupt fixture");
        LlamaCppJniBackend backend = new LlamaCppJniBackend(library, model, 1024, 2, 0);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicBoolean flag = new AtomicBoolean();
        Runnable inference = () -> {
            try {
                String prompt = kind.contains("foreign") ? "foreign-interrupt" : "wait-interrupt";
                backend.infer(new ModelRequest(prompt, 8, Duration.ofSeconds(10)));
                outcome.set(new AssertionError("running native inference ignored interruption"));
            } catch (Throwable result) {
                outcome.set(result);
                flag.set(Thread.interrupted()); // Bypass a deliberately overridden instance query.
                if (flag.get()) Thread.currentThread().interrupt();
            }
        };
        Thread worker = kind.startsWith("virtual") ? Thread.ofVirtual().unstarted(inference)
                : kind.equals("override") ? new Thread(inference) {
                    @Override public boolean isInterrupted() { return false; }
                } : new Thread(inference);
        if (!worker.isVirtual()) worker.setDaemon(true);
        worker.start();
        Throwable failure = null;
        try {
            awaitMarker(ready, worker, outcome);
            worker.interrupt();
            worker.join(3_000);
            check(!worker.isAlive(), "interrupted native worker did not terminate");
            check(outcome.get() instanceof InterruptedException, "native callback did not report InterruptedException: " + outcome.get());
            check(flag.get(), "native callback cleared the caller's interrupt flag");
            check(!Files.exists(Path.of(model + ".completed")), "native decode completed instead of observing cancellation");
        } catch (Exception | Error primary) {
            failure = primary;
            throw primary;
        } finally {
            try { finishWorker(worker); }
            catch (Exception | Error cleanup) {
                if (failure == null) throw cleanup;
                failure.addSuppressed(cleanup);
            } finally {
                if (!worker.isAlive()) backend.close();
                Files.deleteIfExists(ready);
            }
        }
    }

    private static void nativeLockDeadline(Path library, Path base) throws Exception {
        Path model = base.resolve("native-lock.gguf");
        Path ready = Path.of(model + ".ready");
        Files.writeString(model, "synthetic native contention fixture");
        LlamaCppJniBackend backend = new LlamaCppJniBackend(library, model, 1024, 2, 0);
        Field handle = LlamaCppJniBackend.class.getDeclaredField("handle");
        handle.setAccessible(true);
        long id = handle.getLong(backend);
        Method generate = LlamaCppJniBackend.class.getDeclaredMethod("nGenerate",
                long.class, byte[].class, int.class, long.class);
        generate.setAccessible(true);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { backend.infer(new ModelRequest("wait-interrupt", 8, Duration.ofSeconds(10))); }
            catch (Throwable result) { outcome.set(result); }
        }, "native-lock-owner");
        worker.setDaemon(true);
        worker.start();
        Throwable failure = null;
        try {
            awaitMarker(ready, worker, outcome);
            try {
                generate.invoke(null, id, "short".getBytes(StandardCharsets.UTF_8), 8, 50L);
                throw new AssertionError("contended native deadline was ignored");
            } catch (InvocationTargetException wrapped) {
                check(wrapped.getCause() instanceof TimeoutException, "contended native call must time out");
            }
            check(worker.isAlive(), "contended native request waited for the first inference to complete");
            check(!Files.exists(Path.of(model + ".completed")), "contended deadline waited for native work completion");
        } catch (Exception | Error primary) {
            failure = primary;
            throw primary;
        } finally {
            try { finishWorker(worker); }
            catch (Exception | Error cleanup) {
                if (failure == null) throw cleanup;
                failure.addSuppressed(cleanup);
            } finally {
                if (!worker.isAlive()) backend.close();
                Files.deleteIfExists(ready);
            }
        }
    }

    private static void independentClose(Path library, Path base) throws Exception {
        Path closingModel = base.resolve("slow-free.gguf");
        Path otherModel = base.resolve("independent.gguf");
        Path ready = Path.of(closingModel + ".free-ready");
        Files.writeString(closingModel, "synthetic slow teardown");
        Files.writeString(otherModel, "synthetic independent model");
        LlamaCppJniBackend closing = new LlamaCppJniBackend(library, closingModel, 1024, 2, 0);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { closing.close(); }
            catch (Throwable result) { outcome.set(result); }
        }, "native-slow-close");
        worker.setDaemon(true);
        try (var other = new LlamaCppJniBackend(library, otherModel, 1024, 2, 0)) {
            worker.start();
            Throwable failure = null;
            try {
                awaitMarker(ready, worker, outcome);
                check(other.infer(new ModelRequest("independent", 8, Duration.ofMillis(500))).text().equals("OK"),
                        "one model teardown must not block another model lookup");
            } catch (Exception | Error primary) {
                failure = primary;
                throw primary;
            } finally {
                try { finishWorker(worker); }
                catch (Exception | Error cleanup) {
                    if (failure == null) throw cleanup;
                    failure.addSuppressed(cleanup);
                }
            }
            check(outcome.get() == null, "native close failed: " + outcome.get());
        } finally {
            if (!worker.isAlive()) closing.close();
            Files.deleteIfExists(ready);
        }
    }

    private static void awaitMarker(Path marker, Thread worker, AtomicReference<Throwable> outcome) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (deadline - System.nanoTime() > 0) {
            if (Files.isRegularFile(marker) && Files.readString(marker).equals("ready\n")) return;
            if (!worker.isAlive()) throw new AssertionError("native worker ended before readiness", outcome.get());
            Thread.sleep(5);
        }
        throw new AssertionError("native readiness deadline exceeded");
    }

    private static void finishWorker(Thread worker) throws InterruptedException {
        worker.interrupt();
        worker.join(6_000);
        if (worker.isAlive()) throw new AssertionError("native worker did not retire");
    }

    private static void deleteFixtureTree(Path base) throws IOException {
        List<Path> paths;
        try (var walk = Files.walk(base)) { paths = walk.sorted(Comparator.reverseOrder()).toList(); }
        IOException failure = null;
        for (Path path : paths) {
            try { Files.deleteIfExists(path); }
            catch (IOException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
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
