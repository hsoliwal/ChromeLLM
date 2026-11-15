// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Exercises the actual CLI selection without a network call or native model. */
public final class ProviderSelectionContractTestMain {
    private static int checks;

    private ProviderSelectionContractTestMain() { }

    public static void main(String[] args) throws Exception {
        check(StandaloneMain.parse(new String[0]).isEmpty());
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
                "--threads", "--gpu-layers", "--compute-backend"}) {
            reject(() -> StandaloneMain.backend(Map.of("--backend", "gemini", option, "1"),
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
            Files.writeString(prompt, "नमस्ते \uD83D\uDE42");
            check(StandaloneMain.readPrompt(prompt, true).equals("नमस्ते \uD83D\uDE42"));
            check(StandaloneMain.readPrompt(prompt, false).equals("नमस्ते \uD83D\uDE42"));
            Files.write(prompt, new byte[] {(byte) 0xc3, (byte) 0x28});
            rejectIo(() -> StandaloneMain.readPrompt(prompt, true));
            Files.write(prompt, new byte[GeminiApiBackend.MAX_REQUEST_BYTES + 1]);
            rejectIo(() -> StandaloneMain.readPrompt(prompt, true));
            Files.writeString(model, "synthetic fixture, not a model");
            Map<String, String> local = Map.of("--model", model.toString(),
                    "--llama-bin", executable.toString());
            try (ModelBackend backend = StandaloneMain.backend(local, Map.of("GEMINI_API_KEY", "test-key"))) {
                check(backend.id().equals("llama.cpp-cli"));
            }
            reject(() -> StandaloneMain.backend(Map.of("--model", model.toString(),
                    "--llama-bin", executable.toString(), "--compute-backend", "webgpu"), Map.of()));
        } finally {
            Files.deleteIfExists(prompt);
            Files.deleteIfExists(model);
            Files.deleteIfExists(temp);
        }
        System.out.println("provider selection contract checks passed: " + checks);
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

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
