// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
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
        try {
            Files.writeString(model, "synthetic-not-model-weights");
            Files.writeString(rejected, "synthetic-rejected-model");
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
            Files.deleteIfExists(base);
        }
        System.out.println("PASS JNI synthetic lifecycle/UTF-8/timeout/close: " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
