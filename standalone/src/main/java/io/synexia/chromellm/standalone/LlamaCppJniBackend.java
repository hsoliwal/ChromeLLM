// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.ByteBuffer;
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

/** In-process, browser-independent llama.cpp inference through JNI. No ChromeML fallback. */
public final class LlamaCppJniBackend implements ModelBackend {
    private long handle;

    /** Both shared library and licensed GGUF model must be supplied explicitly. */
    public LlamaCppJniBackend(Path jniLibrary, Path model, int contextTokens, int threads, int gpuLayers)
            throws IOException {
        Path bridge = Objects.requireNonNull(jniLibrary, "jniLibrary").toAbsolutePath().normalize();
        Path weights = Objects.requireNonNull(model, "model").toAbsolutePath().normalize();
        if (!Files.isRegularFile(bridge) || !Files.isReadable(bridge)) {
            throw new IOException("JNI library is not a readable file: " + bridge);
        }
        if (!Files.isRegularFile(weights) || !Files.isReadable(weights)) {
            throw new IOException("model is not a readable local file: " + weights);
        }
        if (contextTokens < 32 || contextTokens > 1_048_576 || threads < 1 || threads > 1024
                || gpuLayers < 0 || gpuLayers > 1024) {
            throw new IllegalArgumentException("invalid context, thread count or GPU layer count");
        }
        // Native JNI library is not shipped with this JDK-only module; no auto-discovery or download.
        System.load(bridge.toString());
        this.handle = nOpen(weights.toString().getBytes(StandardCharsets.UTF_8),
                contextTokens, threads, gpuLayers);
        if (handle == 0) throw new IOException("native model loader returned no handle");
    }

    @Override
    public String id() {
        return "llama.cpp-jni";
    }

    @Override
    public synchronized ModelResponse infer(ModelRequest request)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(request, "request");
        if (handle == 0) throw new IOException("native model session is closed");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("interrupted before inference");
        byte[] response = nGenerate(handle, request.prompt().getBytes(StandardCharsets.UTF_8),
                request.maxTokens(), Math.max(1, request.timeout().toMillis()));
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(response)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("native backend returned invalid UTF-8", malformed);
        }
        if (text.isBlank()) throw new IOException("native inference produced no candidate text");
        try {
            String promptHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(request.prompt().getBytes(StandardCharsets.UTF_8)));
            return new ModelResponse(text, id(), promptHash);
        } catch (NoSuchAlgorithmException impossibleOnJava21) {
            throw new IllegalStateException("SHA-256 unavailable", impossibleOnJava21);
        }
    }

    @Override
    public synchronized void close() {
        if (handle != 0) {
            long closing = handle;
            handle = 0;
            nClose(closing);
        }
    }

    private static native long nOpen(byte[] utf8ModelPath, int contextTokens, int threads, int gpuLayers)
            throws IOException;
    private static native byte[] nGenerate(long handle, byte[] utf8Prompt, int maxTokens, long timeoutMillis)
            throws IOException, TimeoutException;
    private static native void nClose(long handle);
}
