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
                ModelResponse exact = backend.infer(
                        new ModelRequest("fixture-whitespace", 16, Duration.ofSeconds(10)));
                check(exact.text().equals("\n  class Example {\n    void f() {}\n  }\n"));
                try {
                    backend.infer(new ModelRequest("fixture-blank", 8, Duration.ofSeconds(10)));
                    throw new AssertionError("blank output accepted");
                } catch (java.io.IOException expected) {
                    checks++;
                }
                try {
                    backend.infer(new ModelRequest("fixture-bad-utf8", 8, Duration.ofSeconds(10)));
                    throw new AssertionError("malformed UTF-8 output accepted");
                } catch (java.io.IOException expected) {
                    checks++;
                }
                try {
                    backend.infer(new ModelRequest("bad-surrogate-" + (char) 0xD800,
                            8, Duration.ofSeconds(10)));
                    throw new AssertionError("malformed UTF-8 input accepted");
                } catch (java.io.IOException expected) {
                    checks++;
                }
                try {
                    backend.infer(new ModelRequest("fixture-oversize", 8, Duration.ofSeconds(10)));
                    throw new AssertionError("oversized stdout accepted");
                } catch (java.io.IOException expected) {
                    checks++;
                }
                try {
                    backend.infer(new ModelRequest("fixture-stderr-overflow", 8, Duration.ofSeconds(10)));
                    throw new AssertionError("oversized stderr accepted");
                } catch (java.io.IOException expected) {
                    checks++;
                }
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
            }
            try (var files = Files.list(temp)) {
                check(files.count() == 1); // No prompt, stdout or stderr survives.
            }
        } finally {
            Files.deleteIfExists(model);
            Files.deleteIfExists(temp);
        }
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
