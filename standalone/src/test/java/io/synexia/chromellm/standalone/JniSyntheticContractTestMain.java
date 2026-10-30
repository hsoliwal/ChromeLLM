// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** JNI lifecycle test against a synthetic llama fixture, not real inference evidence. */
public final class JniSyntheticContractTestMain {
    private JniSyntheticContractTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("pass absolute JNI fixture library path");
        Path model = Files.createTempFile("chromellm-jni-fixture", ".gguf");
        try {
            Files.writeString(model, "synthetic-not-model-weights");
            LlamaCppJniBackend nativeModel = new LlamaCppJniBackend(Path.of(args[0]), model, 1024, 2, 0);
            try (nativeModel) {
                ModelResponse result = nativeModel.infer(new ModelRequest("Unicode: नमस्ते 🦙", 8,
                        Duration.ofSeconds(10)));
                check(result.text().equals("OK"), "fixture output");
                check(result.backendId().equals("llama.cpp-jni"), "provider id");
                check(result.promptSha256().matches("[0-9a-f]{64}"), "receipt");
                try {
                    nativeModel.infer(new ModelRequest("short", 8, Duration.ofMillis(1)));
                    throw new AssertionError("native timeout was not raised");
                } catch (TimeoutException expected) {
                    // Synthetic llama_decode performs a delay and honors the deadline.
                }
            }
            nativeModel.close(); // double-close is allowed
            try {
                nativeModel.infer(new ModelRequest("closed", 8, Duration.ofSeconds(1)));
                throw new AssertionError("closed session was accepted");
            } catch (IOException expected) {
                // Correctly rejects stale native handles.
            }
        } finally {
            Files.deleteIfExists(model);
        }
        System.out.println("JNI synthetic lifecycle/UTF-8/timeout/close tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
