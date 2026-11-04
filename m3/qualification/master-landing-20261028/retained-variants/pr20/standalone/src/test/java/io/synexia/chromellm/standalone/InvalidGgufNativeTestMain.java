// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Proves the real pinned native runtime refuses non-GGUF input without a JVM crash. */
public final class InvalidGgufNativeTestMain {
    private InvalidGgufNativeTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: InvalidGgufNativeTestMain <real-jni-library>");
        }
        Path invalid = Files.createTempFile("chromellm-invalid-gguf-", ".gguf");
        try {
            Files.write(invalid, "NOT_A_GGUF_FILE".getBytes(StandardCharsets.US_ASCII));
            for (int attempt = 0; attempt < 2; ++attempt) {
                boolean refused = false;
                try {
                    JniLlamaCppBackend unexpectedlyOpened =
                            new JniLlamaCppBackend(Path.of(args[0]), invalid);
                    unexpectedlyOpened.close();
                    throw new AssertionError("real JNI accepted an invalid GGUF model");
                } catch (IOException expected) {
                    refused = expected.getMessage() != null
                            && expected.getMessage().contains("unable to load external GGUF model");
                }
                if (!refused) {
                    throw new AssertionError("invalid model refusal was missing or incorrectly classified");
                }
            }
            System.out.println("PASS: pinned real native JNI rejects invalid GGUF twice without crash");
        } finally {
            Files.deleteIfExists(invalid);
        }
    }
}
