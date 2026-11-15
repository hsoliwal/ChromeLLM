// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/** JDK-only executable contract tests run by Maven verify and plain javac/java. */
public final class StandaloneContractTestMain {
    private static int checks;

    private StandaloneContractTestMain() { }

    public static void main(String[] args) throws Exception {
        reject(() -> new ModelRequest(" ", 1, Duration.ofSeconds(1)));
        reject(() -> new ModelRequest("hi", 0, Duration.ofSeconds(1)));
        reject(() -> new ModelRequest("hi", 1, Duration.ZERO));
        reject(() -> new ModelRequest("hi", 1, Duration.ofHours(2)));
        Path temp = Files.createTempDirectory("chromellm-standalone-test-");
        Path model = temp.resolve("fixture.gguf");
        try {
            Files.writeString(model, "not a trained model");
            String java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            String testClasses = Path.of(FakeLlamaCppMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
            try (ModelBackend backend = new LlamaCppCliBackend(
                    List.of(java, "-cp", testClasses, FakeLlamaCppMain.class.getName()), model, temp)) {
                ModelResponse result = backend.infer(new ModelRequest("private input", 8, Duration.ofSeconds(10)));
                check(result.text().equals("synthetic reply"));
                check(result.backendId().equals("llama.cpp-cli"));
                check(result.promptSha256().length() == 64);
                try {
                    backend.infer(new ModelRequest("fail", 8, Duration.ofSeconds(10)));
                    throw new AssertionError("failed process accepted");
                } catch (java.io.IOException expected) {
                    checks++;
                }
                try {
                    backend.infer(new ModelRequest("sleep", 8, Duration.ofMillis(150)));
                    throw new AssertionError("deadline not enforced");
                } catch (TimeoutException expected) {
                    checks++;
                }
                Thread.currentThread().interrupt();
                try {
                    backend.infer(new ModelRequest("sleep", 8, Duration.ofSeconds(10)));
                    throw new AssertionError("interrupted call accepted");
                } catch (InterruptedException expected) {
                    check(Thread.currentThread().isInterrupted());
                } finally {
                    Thread.interrupted();
                }
            }
            try (ModelBackend gpu = new LlamaCppCliBackend(
                    List.of(java, "-cp", testClasses, FakeLlamaCppMain.class.getName()),
                    model, temp, "WebGPU0", 16)) {
                ModelResponse result = gpu.infer(new ModelRequest("check-webgpu", 8, Duration.ofSeconds(10)));
                check(result.text().equals("synthetic reply"));
                check(result.backendId().equals("llama.cpp-cli"));
            }
            reject(() -> new LlamaCppCliBackend(
                    List.of(java, "-cp", testClasses, FakeLlamaCppMain.class.getName()),
                    model, temp, "--unsafe", 16));
            reject(() -> new LlamaCppCliBackend(
                    List.of(java, "-cp", testClasses, FakeLlamaCppMain.class.getName()),
                    model, temp, "WebGPU0", -2));
            try (var files = Files.list(temp)) {
                check(files.count() == 1); // No prompt, stdout or stderr survives.
            }
        } finally {
            Files.deleteIfExists(model);
            Files.deleteIfExists(temp);
        }
        TieredInferenceTestMain.main(new String[0]);
        checks += StdioProtocolContractTestMain.run();
        System.out.println("standalone contract checks passed: " + checks);
    }

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("contract check " + checks);
    }

    private static void reject(Runnable callback) {
        try {
            callback.run();
            throw new AssertionError("invalid request accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }
}
