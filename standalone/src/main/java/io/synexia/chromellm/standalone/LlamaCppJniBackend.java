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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/** In-process, browser-independent llama.cpp inference through JNI. No ChromeML fallback. */
public final class LlamaCppJniBackend implements ModelBackend {
    private long handle;

    /** Names come from the loaded GGML registry, not from an assumed GPU backend. */
    public record Device(String backend, String name, String description, String type) {
        public Device {
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(type, "type");
            if (backend.isBlank() || name.isBlank() || containsControl(backend) || containsControl(name)
                    || !List.of("CPU", "GPU", "IGPU", "ACCEL").contains(type)) {
                throw new IllegalArgumentException("invalid native device identity");
            }
        }

        public boolean gpu() { return type.equals("GPU") || type.equals("IGPU"); }
    }

    /**
     * Observe devices registered by the explicitly supplied JNI library. This does not load a model,
     * download plugins, or prove that a model or operation has executed on any device.
     */
    public static List<Device> availableDevices(Path jniLibrary) throws IOException {
        loadBridge(jniLibrary);
        try {
            return decodeDevices(nAvailableDevices());
        } catch (UnsatisfiedLinkError unsupported) {
            throw new IOException("JNI bridge does not support device enumeration", unsupported);
        }
    }

    /** Both shared library and licensed GGUF model must be supplied explicitly. */
    public LlamaCppJniBackend(Path jniLibrary, Path model, int contextTokens, int threads, int gpuLayers)
            throws IOException {
        this(jniLibrary, model, contextTokens, threads, gpuLayers, "auto", null, null, false);
    }

    /** Select a linked GGML provider policy (auto, cpu or webgpu) without a browser runtime. */
    LlamaCppJniBackend(Path jniLibrary, Path model, int contextTokens, int threads, int gpuLayers,
                       String computeBackend) throws IOException {
        this(jniLibrary, model, contextTokens, threads, gpuLayers,
                Objects.requireNonNull(computeBackend, "computeBackend"), null, null, false);
    }

    /**
     * Select exactly one observed backend/device pair. GPU/IGPU selection requires positive
     * gpuLayers; explicit CPU selection requires zero. Unsupported or ambiguous selection fails.
     * llama.cpp may retain unsupported operations on the CPU; this is an offload target, not an
     * assertion that every operation executes on the selected device.
     */
    public LlamaCppJniBackend(Path jniLibrary, Path model, int contextTokens, int threads, int gpuLayers,
                              String backend, String device) throws IOException {
        this(jniLibrary, model, contextTokens, threads, gpuLayers, "auto", backend, device, true);
    }

    private LlamaCppJniBackend(Path jniLibrary, Path model, int contextTokens, int threads, int gpuLayers,
                               String computeBackend, String backend, String device, boolean explicit)
            throws IOException {
        Path bridge = Objects.requireNonNull(jniLibrary, "jniLibrary").toAbsolutePath().normalize();
        Path weights = Objects.requireNonNull(model, "model").toAbsolutePath().normalize();
        requireReadableBridge(bridge);
        if (!Files.isRegularFile(weights) || !Files.isReadable(weights)) {
            throw new IOException("model is not a readable local file: " + weights);
        }
        if (contextTokens < 32 || contextTokens > 1_048_576 || threads < 1 || threads > 1024
                || gpuLayers < 0 || gpuLayers > 1024) {
            throw new IllegalArgumentException("invalid context, thread count or GPU layer count");
        }
        int selectedBackend = switch (computeBackend) {
            case "auto" -> 0;
            case "cpu" -> 1;
            case "webgpu" -> 2;
            default -> throw new IllegalArgumentException("compute backend must be auto, cpu or webgpu");
        };
        if (selectedBackend == 1 && gpuLayers != 0) {
            throw new IllegalArgumentException("cpu requires zero GPU layers");
        }
        if (selectedBackend == 2 && gpuLayers == 0) {
            throw new IllegalArgumentException("webgpu requires a positive GPU layer count");
        }
        byte[] backendName = explicit ? selector(backend, "backend") : null;
        byte[] deviceName = explicit ? selector(device, "device") : null;
        // Native JNI library is supplied explicitly; no auto-discovery, plugin search or download.
        System.load(bridge.toString());
        byte[] modelPath = encodeNativeInput(weights.toString());
        if (explicit) {
            try {
                this.handle = nOpenOnDevice(modelPath, contextTokens, threads, gpuLayers,
                        backendName, deviceName);
            } catch (UnsatisfiedLinkError unsupported) {
                throw new IOException("JNI bridge does not support explicit device selection", unsupported);
            }
        } else {
            // Keep the original native entry point for existing Java clients and native bridges.
            this.handle = selectedBackend == 0
                    ? nOpen(modelPath, contextTokens, threads, gpuLayers)
                    : nOpenWithBackend(modelPath, contextTokens, threads, gpuLayers, selectedBackend);
        }
        if (handle == 0) throw new IOException("native model loader returned no handle");
    }

    /** The configured, registry-observed offload target; automatic selection returns empty. */
    public synchronized Optional<Device> selectedDevice() throws IOException {
        if (handle == 0) throw new IOException("native model session is closed");
        try {
            List<Device> devices = decodeDevices(nSelectedDevice(handle));
            if (devices.size() > 1) throw new IOException("invalid native selection result");
            return devices.stream().findFirst();
        } catch (UnsatisfiedLinkError unsupported) {
            throw new IOException("JNI bridge does not support device observation", unsupported);
        }
    }

    private static void loadBridge(Path jniLibrary) throws IOException {
        Path bridge = Objects.requireNonNull(jniLibrary, "jniLibrary").toAbsolutePath().normalize();
        requireReadableBridge(bridge);
        // No automatic discovery, download, or loading of additional backend plugins.
        System.load(bridge.toString());
    }

    private static void requireReadableBridge(Path bridge) throws IOException {
        if (!Files.isRegularFile(bridge) || !Files.isReadable(bridge)) {
            throw new IOException("JNI library is not a readable file: " + bridge);
        }
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static byte[] selector(String value, String field) {
        if (value == null || value.isBlank() || containsControl(value) || value.length() > 4096) {
            throw new IllegalArgumentException("invalid " + field + " selector");
        }
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            if (bytes.remaining() > 4096) throw new IllegalArgumentException("oversized " + field + " selector");
            byte[] result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("invalid UTF-16 " + field + " selector", malformed);
        }
    }

    private static List<Device> decodeDevices(byte[][] fields) throws IOException {
        if (fields == null || fields.length % 4 != 0 || fields.length > 4096) {
            throw new IOException("invalid native device inventory");
        }
        List<Device> devices = new ArrayList<>(fields.length / 4);
        for (int i = 0; i < fields.length; i += 4) {
            String[] values = new String[4];
            for (int j = 0; j < 4; j++) {
                byte[] bytes = fields[i + j];
                if (bytes == null || bytes.length > 65536) throw new IOException("invalid native device field");
                try {
                    values[j] = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString();
                } catch (CharacterCodingException malformed) {
                    throw new IOException("native device field is not UTF-8", malformed);
                }
                if (values[j].indexOf('\0') >= 0) throw new IOException("NUL in native device field");
            }
            try { devices.add(new Device(values[0], values[1], values[2], values[3])); }
            catch (IllegalArgumentException malformed) { throw new IOException("invalid native device identity", malformed); }
        }
        return List.copyOf(devices);
    }

    @Override
    public String id() {
        return "llama.cpp-jni";
    }

    @Override
    public synchronized ModelResponse infer(ModelRequest request)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(request, "request");
        long deadline = System.nanoTime() + request.timeout().toNanos();
        if (handle == 0) throw new IOException("native model session is closed");
        checkRequest(deadline);
        byte[] prompt = encodeNativeInput(request.prompt());
        checkRequest(deadline);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("model inference exceeded configured deadline");
        // Round up only the JNI millisecond transport. The Java deadline remains exact.
        long timeoutMillis = Math.max(1, (remaining + 999_999L) / 1_000_000L);
        byte[] response = nGenerate(handle, prompt, request.maxTokens(), timeoutMillis);
        checkRequest(deadline);
        if (response == null || response.length > StdioProtocol.MAX_RESPONSE_BYTES) {
            throw new IOException("native backend returned an invalid response size");
        }
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
                    .digest(prompt));
            ModelResponse result = new ModelResponse(text, id(), promptHash);
            checkRequest(deadline);
            return result;
        } catch (NoSuchAlgorithmException impossibleOnJava21) {
            throw new IllegalStateException("SHA-256 unavailable", impossibleOnJava21);
        }
    }

    private static void checkRequest(long deadline) throws InterruptedException, TimeoutException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("native inference interrupted");
        if (deadline - System.nanoTime() <= 0) {
            throw new TimeoutException("model inference exceeded configured deadline");
        }
    }

    /** Reuse the bounded strict encoder; native tokenization additionally disallows NUL. */
    private static byte[] encodeNativeInput(String value) throws IOException {
        if (value.length() > StdioProtocol.MAX_PROMPT_BYTES) throw new IOException("native input exceeds 16 MiB limit");
        if (value.indexOf('\0') >= 0) throw new IOException("native input has NUL character");
        return StdioProtocol.encode(value, StdioProtocol.MAX_PROMPT_BYTES);
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
    private static native long nOpenWithBackend(byte[] utf8ModelPath, int contextTokens, int threads,
                                               int gpuLayers, int computeBackend) throws IOException;
    private static native long nOpenOnDevice(byte[] utf8ModelPath, int contextTokens, int threads,
                                             int gpuLayers, byte[] backend, byte[] device) throws IOException;
    private static native byte[][] nAvailableDevices() throws IOException;
    private static native byte[][] nSelectedDevice(long handle) throws IOException;
    private static native byte[] nGenerate(long handle, byte[] utf8Prompt, int maxTokens, long timeoutMillis)
            throws IOException, InterruptedException, TimeoutException;
    private static native void nClose(long handle);
}
