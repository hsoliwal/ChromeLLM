// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * An in-process, browser-independent llama.cpp provider. No Chrome, Node, shell,
 * HTTP, JNA or ChromeML dependency is used. Model data and native libraries are
 * supplied by the operator; CPU execution is explicit.
 *
 * <p>The backend serializes inference and close to preserve the native model
 * lifetime. The native deadline is checked between model-evaluation calls; it
 * is not a hard preemption guarantee for a single blocking native call.</p>
 */
public final class JniLlamaCppBackend implements ModelBackend {
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static Path loadedLibrary;
    private long handle;

    public JniLlamaCppBackend(Path library, Path model) throws IOException {
        Objects.requireNonNull(library, "library");
        Objects.requireNonNull(model, "model");
        Path libraryFile = library.toAbsolutePath().normalize();
        Path modelFile = model.toAbsolutePath().normalize();
        if (!Files.isRegularFile(libraryFile) || !Files.isReadable(libraryFile)) {
            throw new IOException("JNI library must be a readable local file");
        }
        if (!Files.isRegularFile(modelFile) || !Files.isReadable(modelFile)) {
            throw new IOException("model must be a readable local file");
        }
        loadOnce(libraryFile);
        // Byte arrays, rather than JNI modified UTF-8 strings, preserve UTF-8 paths.
        handle = nativeOpen(strictUtf8(modelFile.toString()));
        if (handle == 0) {
            throw new IOException("native engine returned a null model handle");
        }
    }

    private static synchronized void loadOnce(Path path) {
        if (loadedLibrary == null) {
            System.load(path.toString());
            loadedLibrary = path;
        } else if (!loadedLibrary.equals(path)) {
            throw new IllegalStateException("a different JNI runtime is already loaded in this classloader");
        }
    }

    @Override
    public String id() {
        return "llama.cpp-jni-cpu";
    }

    @Override
    public synchronized ModelResponse infer(ModelRequest request)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(request, "request");
        if (handle == 0) throw new IllegalStateException("native backend is closed");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("interrupted before inference");
        byte[] prompt = strictUtf8(request.prompt());
        if (prompt.length > 4 * 1024 * 1024) throw new IOException("prompt exceeds 4 MiB");
        long deadlineMillis = Math.max(1, request.timeout().toMillis());
        byte[] output = nativeInfer(handle, prompt, request.maxTokens(), deadlineMillis, MAX_OUTPUT_BYTES);
        if (output == null || output.length == 0 || output.length > MAX_OUTPUT_BYTES) {
            throw new IOException("native runtime returned an empty or oversized response");
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("native runtime returned invalid UTF-8", malformed);
        }
        if (text.isBlank()) throw new IOException("native runtime produced no candidate text");
        return new ModelResponse(text, id(), digest(prompt));
    }

    @Override
    public synchronized void close() {
        long old = handle;
        handle = 0;
        if (old != 0) nativeClose(old);
    }

    private static byte[] strictUtf8(String value) throws IOException {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException malformed) {
            throw new IOException("input is not well-formed UTF-8", malformed);
        }
    }

    private static String digest(byte[] prompt) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(prompt));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 not available", impossible);
        }
    }

    private static native long nativeOpen(byte[] modelUtf8) throws IOException;
    private static native byte[] nativeInfer(long handle, byte[] promptUtf8, int maxTokens,
            long timeoutMillis, int maxOutputBytes) throws IOException, InterruptedException, TimeoutException;
    private static native void nativeClose(long handle);
}
