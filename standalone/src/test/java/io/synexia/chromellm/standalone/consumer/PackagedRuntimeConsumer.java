// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone.consumer;

import io.synexia.chromellm.standalone.LlamaCppJniBackend;
import io.synexia.chromellm.standalone.ModelRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Independent public-API consumer, launched from its own JAR by the packaged JNI smoke test. */
public final class PackagedRuntimeConsumer {
    private PackagedRuntimeConsumer() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("true") || args[0].equals("false"))) {
            throw new IllegalArgumentException("pass the explicit synthetic test flag");
        }
        List<LlamaCppJniBackend.Device> devices = LlamaCppJniBackend.availableDevices();
        if (devices.isEmpty()) {
            throw new AssertionError("packaged Java API returned no registered device");
        }
        if (Boolean.parseBoolean(args[0])) {
            Path fixture = Files.createTempFile("require-default-packaged-fixture-", ".gguf");
            try {
                Files.writeString(fixture, "synthetic fixture, not model weights");
                try (LlamaCppJniBackend backend = LlamaCppJniBackend.fromPackagedRuntime(fixture, 1024, 2, 0)) {
                    if (backend.selectedDevice().isPresent()) {
                        throw new AssertionError("default packaged factory changed automatic device selection");
                    }
                    var response = backend.infer(new ModelRequest("packaged-factory", 8, Duration.ofSeconds(5)));
                    if (!response.text().equals("OK") || !backend.id().equals("llama.cpp-jni")) {
                        throw new AssertionError("packaged factory changed the existing native provider result");
                    }
                }
            } finally {
                Files.deleteIfExists(fixture);
            }
            verifySelectedFactory("require-cpu-packaged-fixture-", 0,
                    observedDevice(devices, "CPU", "CPU"));
            verifySelectedFactory("require-webgpu-packaged-fixture-", 3,
                    observedDevice(devices, "WebGPU", "WebGPU"));
            verifySelectionRefusals();
        }
        System.out.println("PACKAGED_API_READY " + (Boolean.parseBoolean(args[0])
                ? "synthetic factory inference, exact device selection and refusals" : "real device enumeration only"));
    }

    private static LlamaCppJniBackend.Device observedDevice(List<LlamaCppJniBackend.Device> devices,
                                                           String backend, String name) {
        for (LlamaCppJniBackend.Device device : devices) {
            if (device.backend().equals(backend) && device.name().equals(name)) return device;
        }
        throw new AssertionError("synthetic registry is missing " + backend + "/" + name);
    }

    private static void verifySelectedFactory(String fixturePrefix, int gpuLayers,
                                              LlamaCppJniBackend.Device expected) throws Exception {
        // The native fixture rejects loading unless the exact selection reaches model parameters.
        Path fixture = Files.createTempFile(fixturePrefix, ".gguf");
        try {
            Files.writeString(fixture, "synthetic fixture, not model weights");
            try (LlamaCppJniBackend backend = LlamaCppJniBackend.fromPackagedRuntimeOnDevice(
                    fixture, 1024, 2, gpuLayers, expected.backend(), expected.name())) {
                if (!backend.selectedDevice().orElseThrow().equals(expected)) {
                    throw new AssertionError("packaged factory did not retain the observed device");
                }
                for (int turn = 0; turn < 2; turn++) {
                    var response = backend.infer(new ModelRequest(
                            "packaged-selected-" + turn, 8, Duration.ofSeconds(5)));
                    if (!response.text().equals("OK") || !backend.id().equals("llama.cpp-jni")) {
                        throw new AssertionError("packaged device selection did not survive inference");
                    }
                }
            }
        } finally {
            Files.deleteIfExists(fixture);
        }
    }

    private static void verifySelectionRefusals() throws Exception {
        Path fixture = Files.createTempFile("packaged-selection-refusal-", ".gguf");
        try {
            Files.writeString(fixture, "synthetic fixture, not model weights");
            expectSelectionFailure(fixture, 3, "Missing", "GPU0", "not registered");
            expectSelectionFailure(fixture, 3, "WebGPU", "Missing", "not registered");
            expectSelectionFailure(fixture, 3, "Duplicate", "same", "ambiguous");
            expectSelectionFailure(fixture, 0, "WebGPU", "WebGPU", "positive GPU layers");
            expectSelectionFailure(fixture, 1, "CPU", "CPU", "zero GPU layers");
        } finally {
            Files.deleteIfExists(fixture);
        }
    }

    private static void expectSelectionFailure(Path model, int gpuLayers, String backend,
                                                String device, String message) throws Exception {
        try (LlamaCppJniBackend unexpected = LlamaCppJniBackend.fromPackagedRuntimeOnDevice(
                model, 1024, 2, gpuLayers, backend, device)) {
            throw new AssertionError("packaged factory accepted invalid selection: " + unexpected.id());
        } catch (IOException expected) {
            if (!expected.getMessage().contains(message)) {
                throw new AssertionError("unexpected packaged selection refusal", expected);
            }
        }
    }
}
