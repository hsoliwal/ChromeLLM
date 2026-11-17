// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exercises the actual CLI selection without a network call or native model. */
public final class ProviderSelectionContractTestMain {
    private static int checks;

    private ProviderSelectionContractTestMain() { }

    public static void main(String[] args) throws Exception {
        check(StandaloneMain.parse(new String[0]).isEmpty());
        // The browser provider is gone: its options are unknown and its name selects no backend.
        for (String option : new String[] {"--chrome-bin", "--chrome-profile", "--chrome-headless",
                "--chrome-download", "--chrome-status", "--chrome-prepare"}) {
            try {
                StandaloneMain.parse(new String[] {option, "true"});
                throw new AssertionError("removed option accepted: " + option);
            } catch (IllegalArgumentException refused) {
                check(refused.getMessage().equals("unknown option " + option));
            }
        }
        reject(() -> StandaloneMain.parse(new String[] {"--backend"}));
        reject(() -> StandaloneMain.parse(new String[] {"--backend", "cli", "--backend", "gemini"}));
        reject(() -> StandaloneMain.parse(new String[] {"--api-key", "synthetic-secret"}));
        reject(() -> StandaloneMain.parse(new String[] {"--endpoint", "http://example.invalid"}));

        Map<String, String> cloud = StandaloneMain.parse(new String[] {"--backend", "gemini"});
        try (ModelBackend backend = StandaloneMain.backend(cloud, Map.of("GEMINI_API_KEY", "test-key"))) {
            check(backend.id().equals("gemini-api:gemini-4-hydrogen"));
        }
        reject(() -> StandaloneMain.backend(cloud, Map.of()));
        reject(() -> StandaloneMain.backend(cloud, Map.of("GEMINI_API_KEY", "  ")));
        reject(() -> StandaloneMain.backend(
                Map.of("--backend", "gemini", "--api-key-env", "invalid-name"), Map.of()));

        try (ModelBackend backend = StandaloneMain.backend(Map.of(
                "--backend", "gemini", "--api-key-env", "CHROMELLM_GEMINI_KEY",
                "--thinking-level", "xhigh", "--model", "gemini-4-hydrogen"),
                Map.of("CHROMELLM_GEMINI_KEY", "test-key"))) {
            check(backend.id().equals("gemini-api:gemini-4-hydrogen"));
        }
        for (String option : new String[] {"--llama-bin", "--jni-lib", "--context-tokens",
                "--threads", "--gpu-layers", "--compute-backend", "--device-backend", "--device",
                "--tier", "--fast-model", "--reasoning-model", "--frontier-model",
                "--model-url", "--model-sha256", "--model-cache-dir"}) {
            rejectOption(option, () -> StandaloneMain.backend(Map.of("--backend", "gemini", option, "1"),
                    Map.of("GEMINI_API_KEY", "test-key")));
        }
        reject(() -> StandaloneMain.backend(Map.of("--backend", "unexpected"), Map.of()));
        reject(() -> StandaloneMain.backend(Map.of("--thinking-level", "high"),
                Map.of("GEMINI_API_KEY", "test-key")));
        reject(() -> StandaloneMain.backend(Map.of("--api-key-env", "GEMINI_API_KEY"), Map.of()));

        Path temp = Files.createTempDirectory("chromellm-selection-");
        Path model = temp.resolve("model.gguf");
        Path prompt = temp.resolve("prompt.txt");
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        try {
            chromeIsNotABackend(executable, temp);
            Files.writeString(prompt, "नमस्ते \uD83D\uDE42");
            check(StandaloneMain.readPrompt(prompt, true).equals("नमस्ते \uD83D\uDE42"));
            check(StandaloneMain.readPrompt(prompt, false).equals("नमस्ते \uD83D\uDE42"));
            Files.write(prompt, new byte[] {(byte) 0xc3, (byte) 0x28});
            rejectIo(() -> StandaloneMain.readPrompt(prompt, true));
            rejectIo(() -> StandaloneMain.readPrompt(prompt, false));
            Files.write(prompt, new byte[GeminiApiBackend.MAX_REQUEST_BYTES + 1]);
            rejectIo(() -> StandaloneMain.readPrompt(prompt, true));
            Files.writeString(model, "synthetic fixture, not a model");
            Map<String, String> local = Map.of("--model", model.toString(),
                    "--llama-bin", executable.toString());
            try (ModelBackend backend = StandaloneMain.backend(local, Map.of("GEMINI_API_KEY", "test-key"))) {
                check(backend.id().equals("llama.cpp-cli"));
            }
            try (ModelBackend deferred = StandaloneMain.backend(Map.of(
                    "--model-url", "https://example.invalid/test.gguf",
                    "--model-sha256", "0".repeat(64),
                    "--model-cache-dir", temp.toString(),
                    "--llama-bin", executable.toString()), Map.of())) {
                check(deferred.id().equals("llama.cpp-cli"));
            }
            reject(() -> StandaloneMain.backend(Map.of(
                    "--model-url", "https://example.invalid/test.gguf",
                    "--model-cache-dir", temp.toString(),
                    "--llama-bin", executable.toString()), Map.of()));
            reject(() -> StandaloneMain.backend(Map.of(
                    "--model-url", "https://example.invalid/test.gguf",
                    "--model-sha256", "0".repeat(64),
                    "--model-cache-dir", temp.toString(),
                    "--model", model.toString(),
                    "--llama-bin", executable.toString()), Map.of()));
            reject(() -> StandaloneMain.backend(Map.of("--model", model.toString(),
                    "--llama-bin", executable.toString(), "--compute-backend", "webgpu"), Map.of()));
            for (String option : new String[] {"--jni-lib", "--context-tokens", "--threads",
                    "--device-backend"}) {
                Map<String, String> incompatible = new HashMap<>(local);
                incompatible.put(option, "1");
                rejectOption(option, () -> StandaloneMain.backend(incompatible, Map.of()));
            }
            rejectOption("--llama-bin", () -> StandaloneMain.backend(Map.of("--backend", "jni",
                    "--model", model.toString(), "--llama-bin", executable.toString()), Map.of()));

            // Supported CLI device, GPU and tier options must survive provider validation.
            for (Map<String, String> supported : List.of(
                    Map.of("--device", "WebGPU0"),
                    Map.of("--gpu-layers", "1"),
                    Map.of("--device", "WebGPU0", "--gpu-layers", "1"))) {
                Map<String, String> selected = new HashMap<>(local);
                selected.putAll(supported);
                try (ModelBackend backend = StandaloneMain.backend(selected, Map.of())) {
                    check(backend.id().equals("llama.cpp-cli"));
                }
            }
            for (String tier : new String[] {"fast", "reasoning", "frontier"}) {
                try (ModelBackend backend = StandaloneMain.backend(Map.of("--llama-bin", executable.toString(),
                        "--tier", tier, "--" + tier + "-model", model.toString()), Map.of())) {
                    check(backend.id().equals("llama.cpp-cli"));
                }
            }
            promptBoundary(prompt, true, GeminiApiBackend.MAX_REQUEST_BYTES);
            promptBoundary(prompt, false, StdioProtocol.MAX_PROMPT_BYTES);
        } finally {
            Files.deleteIfExists(prompt);
            Files.deleteIfExists(model);
            Files.deleteIfExists(temp);
        }
        ModelArtifactCacheContractTestMain.main(new String[0]);
        McpStdioServerContractTestMain.main(new String[0]);
        AgentPlanContractTestMain.main(new String[0]);
        System.out.println("provider selection contract checks passed: " + checks);
    }

    private static void chromeIsNotABackend(Path executable, Path temp) throws Exception {
        Path model = Files.writeString(temp.resolve("chrome-model.gguf"), "synthetic");
        try {
            StandaloneMain.backend(Map.of("--backend", "chrome", "--model", model.toString(),
                    "--llama-bin", executable.toString()), Map.of()).close();
            throw new AssertionError("chrome selected a backend");
        } catch (IllegalArgumentException refused) {
            check(refused.getMessage().contains("unknown backend (expected cli, jni or gemini)"));
        } finally {
            Files.deleteIfExists(model);
        }
    }

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("provider selection check " + checks);
    }

    private static void reject(CheckedAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("invalid provider configuration accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void rejectIo(CheckedAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("invalid prompt file accepted");
        } catch (IOException expected) {
            checks++;
        }
    }

    private static void rejectOption(String option, CheckedAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("incompatible option accepted: " + option);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().equals(option + " is not supported by the selected backend"));
        }
    }

    private static void promptBoundary(Path prompt, boolean hosted, int limit) throws Exception {
        byte[] bytes = new byte[limit];
        Arrays.fill(bytes, (byte) 'a');
        // End exactly at the byte budget with one supplementary Unicode code point.
        bytes[limit - 4] = (byte) 0xf0;
        bytes[limit - 3] = (byte) 0x9f;
        bytes[limit - 2] = (byte) 0x99;
        bytes[limit - 1] = (byte) 0x82;
        Files.write(prompt, bytes);
        String decoded = StandaloneMain.readPrompt(prompt, hosted);
        check(decoded.length() == limit - 2 && decoded.endsWith("\uD83D\uDE42"));
        Files.write(prompt, new byte[] {'a'}, StandardOpenOption.APPEND);
        rejectIo(() -> StandaloneMain.readPrompt(prompt, hosted));
        // A bounded byte count must not accept a truncated final UTF-8 sequence.
        Files.write(prompt, Arrays.copyOf(bytes, bytes.length - 1));
        rejectIo(() -> StandaloneMain.readPrompt(prompt, hosted));
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
