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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Synthetic transport/lifecycle proof, not proof of real llama.cpp inference. */
public final class JniContractTestMain {
    private JniContractTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 && (args.length != 2
                || !args[1].equals("--expect-abi-mismatch"))) {
            throw new IllegalArgumentException(
                    "usage: JniContractTestMain <fixture> [--expect-abi-mismatch]");
        }
        Path model = Files.createTempFile("chromellm-model-fixture-", ".gguf");
        try {
            if (args.length == 2) {
                boolean refused = false;
                try (JniLlamaCppBackend ignored = new JniLlamaCppBackend(Path.of(args[0]), model)) {
                    throw new AssertionError("incompatible native ABI accepted by " + ignored.id());
                } catch (IOException expected) {
                    refused = expected.getMessage().contains("unsupported native JNI ABI version");
                }
                if (!refused) throw new AssertionError("incompatible native ABI not diagnosed");
                System.out.println("PASS: mismatched native JNI ABI rejected before model open");
                return;
            }
            JniLlamaCppBackend backend = new JniLlamaCppBackend(Path.of(args[0]), model);
            try {
                nativeHandleChecks(model);
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
                // A millisecond-only JNI ABI cannot round positive nanos up.
                // The synthetic provider would otherwise return a candidate.
                for (long nanos : new long[] {1L, 999L, 999_999L}) {
                    boolean tooShort = false;
                    try {
                        backend.infer(new ModelRequest("fixture-whitespace", 8,
                                Duration.ofNanos(nanos)));
                    } catch (TimeoutException expectedException) {
                        tooShort = true;
                    }
                    if (!tooShort) throw new AssertionError(
                            "native ABI extended a sub-millisecond deadline: " + nanos);
                }
                ModelResponse boundary = backend.infer(new ModelRequest(
                        "fixture-whitespace", 8, Duration.ofMillis(1)));
                if (!boundary.text().equals(expectedSource))
                    throw new AssertionError("whole-millisecond JNI request refused");
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

    /** Exercise the native boundary directly; the ordinary Java monitor is not the oracle. */
    private static void nativeHandleChecks(Path model) throws Exception {
        Method open = JniLlamaCppBackend.class.getDeclaredMethod("nativeOpen", byte[].class);
        Method infer = JniLlamaCppBackend.class.getDeclaredMethod("nativeInfer", long.class,
                byte[].class, int.class, long.class, int.class);
        Method close = JniLlamaCppBackend.class.getDeclaredMethod("nativeClose", long.class);
        open.setAccessible(true);
        infer.setAccessible(true);
        close.setAccessible(true);
        byte[] prompt = "handle-probe".getBytes(StandardCharsets.UTF_8);
        int checks = 0;
        // The synthetic baseline never dereferences State in generate, so this
        // first refusal is a safe deterministic negative control for the old code.
        for (long invalid : new long[] {Long.MAX_VALUE, Long.MIN_VALUE, -1, 0}) {
            expectClosed(infer, invalid, prompt);
            checks++;
        }
        invokeNative(close, 0L);
        invokeNative(close, -1L);
        invokeNative(close, Long.MAX_VALUE);
        checks++;
        long previous = 0;
        for (int i = 0; i < 128; i++) {
            long handle = (Long) invokeNative(open,
                    model.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
            try {
                if (handle <= previous) throw new AssertionError("native handle was reused or wrapped");
                previous = handle;
                checks++;
                byte[] output = (byte[]) invokeNative(infer, handle, prompt, 8, 5000L, 4096);
                if (!new String(output, StandardCharsets.UTF_8).equals("jni-fixture:handle-probe"))
                    throw new AssertionError("valid native handle failed");
                checks++;
            } finally {
                invokeNative(close, handle);
            }
            invokeNative(close, handle);
            expectClosed(infer, handle, prompt);
            checks++;
        }
        System.out.println("PASS: native handle boundary checks=" + checks);
    }

    private static void expectClosed(Method infer, long handle, byte[] prompt) throws Exception {
        try {
            invokeNative(infer, handle, prompt, 8, 5000L, 4096);
            throw new AssertionError("fabricated or stale native handle accepted: " + handle);
        } catch (IOException expected) {
            if (!expected.getMessage().equals("closed or unknown native model handle"))
                throw new AssertionError("wrong native handle failure", expected);
        }
    }

    private static Object invokeNative(Method method, Object... arguments) throws Exception {
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new AssertionError("unexpected native throwable", cause);
        }
    }
}
