// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        Path promptFile = Files.createTempFile(scratch, "chromellm-prompt-", ".txt");
        Path stdoutFile = null;
        Path stderrFile = null;
        try {
            Files.writeString(promptFile, request.prompt(), StandardCharsets.UTF_8);
            stdoutFile = Files.createTempFile(scratch, "chromellm-stdout-", ".txt");
            stderrFile = Files.createTempFile(scratch, "chromellm-stderr-", ".txt");
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
        } finally {
            Files.deleteIfExists(promptFile);
            if (stdoutFile != null) Files.deleteIfExists(stdoutFile);
            if (stderrFile != null) Files.deleteIfExists(stderrFile);
        }
    }

    private Process startProcess(ModelRequest request, Path prompt, Path stdout, Path stderr) throws IOException {
        List<String> args = new ArrayList<>(launcher);
        args.addAll(List.of("-m", model.toString(), "-f", prompt.toString(), "-n",
                Integer.toString(request.maxTokens()), "--no-display-prompt", "--no-show-timings",
                "--simple-io", "--single-turn"));
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
