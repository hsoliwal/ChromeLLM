// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Browser-free llama.cpp CLI adapter. The executable and licensed GGUF model are external. */
public final class LlamaCppCliBackend implements ModelBackend {
    private static final int MAX_STDOUT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 64 * 1024;
    private static final String BACKEND_ID = "llama.cpp-cli";
    private final List<String> launcher;
    private final Path model;
    private final Path scratch;

    public LlamaCppCliBackend(Path executable, Path model) {
        this(List.of(Objects.requireNonNull(executable, "executable").toAbsolutePath().toString()),
                model, Path.of(System.getProperty("java.io.tmpdir")));
        if (!Files.isRegularFile(executable)) {
            throw new IllegalArgumentException("llama.cpp executable is not a regular file");
        }
    }

    /** Explicit argv prefix also supports test launchers without shell interpretation. */
    public LlamaCppCliBackend(List<String> launcher, Path model, Path scratch) {
        this.launcher = List.copyOf(Objects.requireNonNull(launcher, "launcher"));
        this.model = Objects.requireNonNull(model, "model").toAbsolutePath().normalize();
        this.scratch = Objects.requireNonNull(scratch, "scratch").toAbsolutePath().normalize();
        if (this.launcher.isEmpty() || this.launcher.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new IllegalArgumentException("launcher must contain nonblank argv elements");
        }
        if (!Files.isRegularFile(this.model) || !Files.isReadable(this.model)) {
            throw new IllegalArgumentException("model must be a readable local file");
        }
        if (!Files.isDirectory(this.scratch) || !Files.isWritable(this.scratch)) {
            throw new IllegalArgumentException("scratch directory must exist and be writable");
        }
    }

    @Override
    public String id() {
        return BACKEND_ID;
    }

    @Override
    public ModelResponse infer(ModelRequest request) throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(request, "request");
        byte[] promptBytes = strictUtf8(request.prompt());
        if (promptBytes.length > 4 * 1024 * 1024) {
            throw new IOException("prompt exceeds 4 MiB");
        }
        Path promptFile = Files.createTempFile(scratch, "chromellm-prompt-", ".txt");
        try {
            Files.write(promptFile, promptBytes);
            return runBoundedProcess(request, promptFile, promptBytes);
        } finally {
            Files.deleteIfExists(promptFile);
        }
    }

    private ModelResponse runBoundedProcess(ModelRequest request, Path promptFile, byte[] promptBytes)
            throws IOException, InterruptedException, TimeoutException {
        List<String> args = new ArrayList<>(launcher);
        args.addAll(List.of("-m", model.toString(), "-f", promptFile.toString(), "-n",
                Integer.toString(request.maxTokens()), "--no-display-prompt", "--no-show-timings",
                "--simple-io", "--single-turn"));
        Process process = new ProcessBuilder(args).start();
        long deadline = System.nanoTime() + request.timeout().toNanos();
        CompletableFuture<byte[]> stdout = capture(process.getInputStream(), MAX_STDOUT_BYTES);
        CompletableFuture<byte[]> stderr = capture(process.getErrorStream(), MAX_STDERR_BYTES);
        try {
            process.getOutputStream().close(); // No stdin prompts or arbitrary commands.
            boolean finished = false;
            while (!finished) {
                if (stdout.isCompletedExceptionally() || stderr.isCompletedExceptionally()) {
                    throw new IOException("llama.cpp subprocess output exceeded the configured limit or failed");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("model inference exceeded configured deadline");
                }
                long slice = Math.max(1, Math.min(50, TimeUnit.NANOSECONDS.toMillis(remaining)));
                finished = process.waitFor(slice, TimeUnit.MILLISECONDS);
            }
            byte[] output = awaitCapture(stdout, deadline);
            awaitCapture(stderr, deadline);
            if (process.exitValue() != 0) {
                throw new IOException("llama.cpp CLI exited with status " + process.exitValue());
            }
            if (output.length == 0) {
                throw new IOException("llama.cpp CLI produced no candidate text");
            }
            String text = strictDecode(output);
            if (text.isBlank()) {
                throw new IOException("llama.cpp CLI produced no candidate text");
            }
            return new ModelResponse(text, id(), sha256(promptBytes));
        } finally {
            stopProcess(process);
        }
    }

    private static CompletableFuture<byte[]> capture(InputStream stream, int maximum) {
        CompletableFuture<byte[]> captured = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (InputStream source = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (count > maximum - out.size()) {
                        throw new IOException("subprocess stream exceeded configured byte limit");
                    }
                    out.write(buffer, 0, count);
                }
                captured.complete(out.toByteArray());
            } catch (IOException | RuntimeException error) {
                captured.completeExceptionally(error);
            }
        });
        return captured;
    }

    private static byte[] awaitCapture(CompletableFuture<byte[]> capture, long deadline)
            throws IOException, InterruptedException, TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("subprocess stream deadline exceeded");
        try {
            return capture.get(remaining, TimeUnit.NANOSECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) throw io;
            throw new IOException("subprocess stream failed", failure.getCause());
        }
    }

    private static void stopProcess(Process process) {
        // Best-effort descendant containment; OS-specific job objects/cgroups
        // remain necessary for a complete process-tree guarantee.
        for (ProcessHandle descendant : process.descendants().toList().reversed()) {
            descendant.destroyForcibly();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            // Avoid racing prompt-file removal while Windows retains child handles.
            boolean interrupted = Thread.interrupted();
            try {
                process.waitFor(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException stopInterrupted) {
                interrupted = true;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
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

    private static String strictDecode(byte[] value) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("CLI runtime returned invalid UTF-8", malformed);
        }
    }

    private static String sha256(byte[] prompt) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(prompt));
        } catch (NoSuchAlgorithmException impossibleOnJava21) {
            throw new IllegalStateException("SHA-256 unavailable", impossibleOnJava21);
        }
    }
}
