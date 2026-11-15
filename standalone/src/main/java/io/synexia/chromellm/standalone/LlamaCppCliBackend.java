// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Browser-free llama.cpp CLI adapter. The executable and licensed GGUF model are external. */
public final class LlamaCppCliBackend implements ModelBackend {
    private static final long MAX_STDOUT_BYTES = 4L * 1024 * 1024;
    private static final String BACKEND_ID = "llama.cpp-cli";
    private static final long SCRATCH_DELETE_SECONDS = 5;
    private static final long SCRATCH_DELETE_RETRY_MILLIS = 20;
    private final List<String> launcher;
    private final Path model;
    private final Path scratch;
    private final String device;
    private final int gpuLayers;

    public LlamaCppCliBackend(Path executable, Path model) {
        this(List.of(Objects.requireNonNull(executable, "executable").toAbsolutePath().toString()),
                model, Path.of(System.getProperty("java.io.tmpdir")));
        if (!Files.isRegularFile(executable)) {
            throw new IllegalArgumentException("llama.cpp executable is not a regular file");
        }
    }

    /** Explicit native-device selection without changing legacy CLI defaults. */
    public LlamaCppCliBackend(Path executable, Path model, String device, int gpuLayers) {
        this(List.of(Objects.requireNonNull(executable, "executable").toAbsolutePath().toString()),
                model, Path.of(System.getProperty("java.io.tmpdir")), device, gpuLayers);
        if (!Files.isRegularFile(executable)) {
            throw new IllegalArgumentException("llama.cpp executable is not a regular file");
        }
    }

    /** Explicit argv prefix also supports test launchers without shell interpretation. */
    public LlamaCppCliBackend(List<String> launcher, Path model, Path scratch) {
        this(launcher, model, scratch, null, -1);
    }

    /** Explicit GGML device selection; the llama.cpp executable supplies any Dawn/WebGPU backend. */
    public LlamaCppCliBackend(List<String> launcher, Path model, Path scratch, String device, int gpuLayers) {
        this.launcher = List.copyOf(Objects.requireNonNull(launcher, "launcher"));
        this.model = Objects.requireNonNull(model, "model").toAbsolutePath().normalize();
        this.scratch = Objects.requireNonNull(scratch, "scratch").toAbsolutePath().normalize();
        if (device != null && !device.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("device must be an exact, bounded GGML device name");
        }
        if (gpuLayers < -1 || gpuLayers > 1024) {
            throw new IllegalArgumentException("GPU layers must be omitted or in [0, 1024]");
        }
        this.device = device;
        this.gpuLayers = gpuLayers;
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
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("model inference interrupted before start");
        }
        try (InferenceFiles files = new InferenceFiles()) {
            Path promptFile = files.create(scratch, "chromellm-prompt-");
            Files.writeString(promptFile, request.prompt(), StandardCharsets.UTF_8);
            Path stdoutFile = files.create(scratch, "chromellm-stdout-");
            Path stderrFile = files.create(scratch, "chromellm-stderr-");
            Process process = startProcess(request, promptFile, stdoutFile, stderrFile);
            awaitProcess(process, request);
            if (process.exitValue() != 0) {
                throw new IOException("llama.cpp CLI exited with status " + process.exitValue());
            }
            if (Files.size(stdoutFile) > MAX_STDOUT_BYTES) {
                throw new IOException("llama.cpp CLI output exceeds bounded limit");
            }
            String text = Files.readString(stdoutFile, StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                throw new IOException("llama.cpp CLI produced no candidate text");
            }
            return new ModelResponse(text, id(), sha256(request.prompt()));
        }
    }

    /** Owns only the three files created for one invocation; never follows replacements. */
    private static final class InferenceFiles implements AutoCloseable {
        private final Path[] paths = new Path[3];
        private int count;

        private Path create(Path directory, String prefix) throws IOException {
            if (count == paths.length) throw new IllegalStateException("inference file limit exceeded");
            Path file = Files.createTempFile(directory, prefix, ".txt");
            paths[count++] = file;
            return file;
        }

        @Override
        public void close() throws IOException {
            Exception failure = null;
            for (int i = 0; i < count; i++) {
                try {
                    deleteScratch(paths[i]);
                } catch (IOException | RuntimeException cleanup) {
                    if (failure == null) failure = cleanup;
                    else failure.addSuppressed(cleanup);
                }
            }
            // try-with-resources retains an inference failure and suppresses this cleanup
            // failure; without a primary failure, callers still learn that cleanup failed.
            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
        }
    }

    /**
     * Deletes one scratch file. Windows can report a redirected file as in use for a short
     * time after its child process has exited, so a busy file is retried within a bound.
     * A path that was replaced by a non-empty directory is not busy and fails at once.
     */
    private static void deleteScratch(Path file) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SCRATCH_DELETE_SECONDS);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    Files.deleteIfExists(file);
                    return;
                } catch (DirectoryNotEmptyException replaced) {
                    throw replaced;
                } catch (FileSystemException busy) {
                    if (System.nanoTime() >= deadline) throw busy;
                }
                try {
                    Thread.sleep(SCRATCH_DELETE_RETRY_MILLIS);
                } catch (InterruptedException duringCleanup) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private Process startProcess(ModelRequest request, Path prompt, Path stdout, Path stderr) throws IOException {
        List<String> args = new ArrayList<>(launcher);
        args.addAll(List.of("-m", model.toString(), "-f", prompt.toString(), "-n",
                Integer.toString(request.maxTokens()), "--no-display-prompt", "--no-show-timings",
                "--simple-io", "--single-turn"));
        if (device != null) args.addAll(List.of("--device", device));
        if (gpuLayers >= 0) args.addAll(List.of("-ngl", Integer.toString(gpuLayers)));
        return new ProcessBuilder(args)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
    }

    private static void awaitProcess(Process process, ModelRequest request)
            throws InterruptedException, TimeoutException {
        try {
            if (!process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                stopProcess(process);
                throw new TimeoutException("model inference exceeded configured deadline");
            }
        } catch (InterruptedException interrupted) {
            stopProcess(process);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static void stopProcess(Process process) {
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
        // Termination is asynchronous. Wait for handle release before deleting redirected
        // files, especially on Windows where an exiting child can still hold them open.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean interrupted = false;
        while (process.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                process.waitFor(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException duringCleanup) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        // Child-process trees require a separate operating-system-specific containment policy.
    }

    private static String sha256(String prompt) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(prompt.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossibleOnJava21) {
            throw new IllegalStateException("SHA-256 unavailable", impossibleOnJava21);
        }
    }
}
