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
        byte[] promptBytes = strictUtf8(request.prompt());
        if (promptBytes.length > 4 * 1024 * 1024) {
            throw new IOException("prompt exceeds 4 MiB");
        }
        Path promptFile = Files.createTempFile(scratch, "chromellm-prompt-", ".txt");
        Path stdoutFile = null;
        Path stderrFile = null;
        try {
            Files.write(promptFile, promptBytes);
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
            String text = strictDecode(Files.readAllBytes(stdoutFile));
            if (text.isBlank()) {
                throw new IOException("llama.cpp CLI produced no candidate text");
            }
            return new ModelResponse(text, id(), sha256(promptBytes));
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
