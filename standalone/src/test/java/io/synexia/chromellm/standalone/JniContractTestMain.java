// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Synthetic transport/lifecycle proof, not proof of real llama.cpp inference. */
public final class JniContractTestMain {
    private JniContractTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: JniContractTestMain <fixture.so>");
        Path model = Files.createTempFile("chromellm-model-fixture-", ".gguf");
        try {
            JniLlamaCppBackend backend = new JniLlamaCppBackend(Path.of(args[0]), model);
            try {
                ModelRequest request = new ModelRequest("utf8 Ελληνικά 🌿", 8, Duration.ofSeconds(5));
                ModelResponse result = backend.infer(request);
                if (!Objects.equals(result.text(), "jni-fixture:utf8 Ελληνικά 🌿"))
                    throw new AssertionError("JNI UTF-8 roundtrip failed");
                if (!result.backendId().equals("llama.cpp-jni-cpu"))
                    throw new AssertionError("incorrect backend identity");
                String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(request.prompt().getBytes(StandardCharsets.UTF_8)));
                if (!result.promptSha256().equals(expected))
                    throw new AssertionError("wrong prompt binding");
                // Preserve source text exactly, including leading and trailing newlines.
                ModelResponse exact = backend.infer(
                        new ModelRequest("fixture-whitespace", 16, Duration.ofSeconds(5)));
                String expectedSource = "\n  class Example {\n    void f() {}\n  }\n";
                if (!Objects.equals(exact.text(), expectedSource))
                    throw new AssertionError("JNI candidate output was trimmed or changed");
                boolean nativeInterrupted = false;
                try { backend.infer(new ModelRequest("fixture-interrupted", 8, Duration.ofSeconds(5))); }
                catch (InterruptedException expectedException) { nativeInterrupted = true; }
                if (!nativeInterrupted) throw new AssertionError("JNI interrupt not mapped");
                boolean timeout = false;
                try { backend.infer(new ModelRequest("fixture-timeout", 8, Duration.ofSeconds(5))); }
                catch (TimeoutException expectedException) { timeout = true; }
                if (!timeout) throw new AssertionError("native deadline not mapped");
                boolean nativeError = false;
                try { backend.infer(new ModelRequest("fixture-native-error", 8, Duration.ofSeconds(5))); }
                catch (IOException expectedException) { nativeError = true; }
                if (!nativeError) throw new AssertionError("native error not mapped");
                boolean invalidUtf8 = false;
                try { backend.infer(new ModelRequest("fixture-invalid-utf8", 8, Duration.ofSeconds(5))); }
                catch (IOException expectedException) { invalidUtf8 = true; }
                if (!invalidUtf8) throw new AssertionError("invalid UTF-8 not rejected");
                boolean malformedInputRejected = false;
                try {
                    backend.infer(new ModelRequest("fixture-invalid-surrogate-" + (char) 0xD800,
                            8, Duration.ofSeconds(5)));
                } catch (IOException expectedException) {
                    malformedInputRejected = true;
                }
                if (!malformedInputRejected)
                    throw new AssertionError("malformed Java input was silently replaced during UTF-8 encoding");
                backend.close();
                backend.close();
                boolean rejected = false;
                try { backend.infer(request); } catch (IllegalStateException closedException) { rejected = true; }
                if (!rejected) throw new AssertionError("infer after close was accepted");
            } finally {
                backend.close();
            }
            System.out.println("PASS: JNI UTF-8, exact output, digest, interrupt/timeout, failure mapping, close/reuse");
        } finally {
            Files.deleteIfExists(model);
        }
    }
}
