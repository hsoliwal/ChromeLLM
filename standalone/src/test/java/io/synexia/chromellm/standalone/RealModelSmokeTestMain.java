// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** Real downloaded model and real JNI execution; invoked only by the real-model Maven profile. */
public final class RealModelSmokeTestMain {
    private RealModelSmokeTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[2].equals("cpu") || args[2].equals("webgpu"))) {
            throw new IllegalArgumentException("expected JNI library or CMake path file, model cache, cpu|webgpu");
        }
        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        Path library = input.toString().endsWith(".txt") ? Path.of(Files.readString(input).strip()) : input;
        Path model = new ModelArtifactCache(NativeBuildArtifacts.MODEL_URL, NativeBuildArtifacts.MODEL_SHA,
                Path.of(args[1])).resolveWithOfflinePolicy(true);
        if (Files.size(model) != NativeBuildArtifacts.MODEL_BYTES) throw new AssertionError("wrong model size");
        boolean webgpu = args[2].equals("webgpu");
        var device = LlamaCppJniBackend.availableDevices(library).stream()
                .filter(d -> webgpu ? d.backend().equalsIgnoreCase("WebGPU") && d.gpu() : d.type().equals("CPU"))
                .findFirst().orElseThrow(() -> new AssertionError("requested real compute device unavailable: " + args[2]));
        String prompt = "<|im_start|>user\nComplete the sentence: The sky is<|im_end|>\n<|im_start|>assistant\n";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(prompt.getBytes(StandardCharsets.UTF_8)));
        try (var backend = new LlamaCppJniBackend(library, model, 512, 2, webgpu ? 1 : 0,
                device.backend(), device.name())) {
            if (!backend.selectedDevice().orElseThrow().equals(device)) throw new AssertionError("device selection changed");
            ModelResponse response = backend.infer(new ModelRequest(prompt, 8, Duration.ofSeconds(120)));
            if (response.text().isBlank() || !response.backendId().equals(backend.id())
                    || !response.promptSha256().equals(hash)) throw new AssertionError("invalid real inference response");
            System.out.println("Real model JNI smoke passed: " + device.backend() + "/" + device.name()
                    + ", response UTF-16 units=" + response.text().length());
        }
    }
}
