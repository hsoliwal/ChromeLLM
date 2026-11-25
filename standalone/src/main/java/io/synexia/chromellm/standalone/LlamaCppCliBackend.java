// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Browser-free llama.cpp CLI adapter. The executable and licensed GGUF model are external. */
public final class LlamaCppCliBackend implements ModelBackend {
    private static final long MAX_STDOUT_BYTES = 4L * 1024 * 1024;
    private static final long OUTPUT_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final String BACKEND_ID = "llama.cpp-cli";
    private static final long SCRATCH_DELETE_SECONDS = 5;
    private static final long SCRATCH_DELETE_RETRY_MILLIS = 20;
    /** llama-cli (the chat CLI since llama.cpp b6xxx) still prints a banner and echoes the prompt. */
    private static final List<String> CHAT_CLI_FLAGS =
            List.of("--no-display-prompt", "--no-show-timings", "--simple-io", "--single-turn");
    /** llama-completion is the classic one-shot tool; it rejects --no-show-timings. */
    private static final List<String> COMPLETION_FLAGS = List.of("--no-display-prompt", "--simple-io", "--single-turn");
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final String END_OF_TEXT = "[end of text]";
    private static final String CHAT_EXIT = "\n\nExiting...\n";
    private static final int CHAT_ECHO_BYTES = 500;
    private static final String ECHO_MARK = "> ";
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
        long deadline = System.nanoTime() + request.timeout().toNanos();
        byte[] prompt = StdioProtocol.encode(request.prompt(), StdioProtocol.MAX_PROMPT_BYTES);
        remaining(deadline);
        try (InferenceFiles files = new InferenceFiles(); ProcessScope processScope = new ProcessScope()) {
            Path promptFile = files.create(scratch, "chromellm-prompt-");
            Files.write(promptFile, prompt);
            remaining(deadline);
            checkInterrupted();
            Process process = processScope.start(request, promptFile);
            byte[] output = awaitProcess(process, deadline);
            String text = reply(output, request.prompt(), completionTool(launcher));
            if (text.isEmpty()) {
                throw new IOException("llama.cpp CLI produced no candidate text");
            }
            ModelResponse result = new ModelResponse(text, id(), sha256(prompt));
            remaining(deadline);
            checkInterrupted();
            return result;
        }
    }

    /** Owns only the prompt file created for one invocation; never follows replacements. */
    private static final class InferenceFiles implements AutoCloseable {
        private final Path[] paths = new Path[1];
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

    /** Stop the owned child and release its handles before deleting its prompt. */
    private final class ProcessScope implements AutoCloseable {
        private Process process;

        private Process start(ModelRequest request, Path prompt) throws IOException {
            process = startProcess(request, prompt);
            return process;
        }

        @Override
        public void close() throws IOException {
            if (process == null) return;
            Exception failure = null;
            try {
                stopProcess(process);
            } catch (IOException | RuntimeException cleanup) {
                failure = cleanup;
            }
            Closeable[] streams = {process.getOutputStream(), process.getInputStream(), process.getErrorStream()};
            for (Closeable stream : streams) {
                try {
                    stream.close();
                } catch (IOException | RuntimeException cleanup) {
                    if (failure == null) failure = cleanup;
                    else if (failure != cleanup) failure.addSuppressed(cleanup);
                }
            }
            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
        }
    }

    /**
     * Deletes one scratch file. Windows can report a child-owned file as in use for a short
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

    private Process startProcess(ModelRequest request, Path prompt) throws IOException {
        List<String> args = new ArrayList<>(launcher);
        args.addAll(List.of("-m", model.toString(), "-f", prompt.toString(), "-n",
                Integer.toString(request.maxTokens())));
        args.addAll(completionTool(launcher) ? COMPLETION_FLAGS : CHAT_CLI_FLAGS);
        if (device != null) args.addAll(List.of("--device", device));
        if (gpuLayers >= 0) args.addAll(List.of("-ngl", Integer.toString(gpuLayers)));
        return new ProcessBuilder(args)
                // Diagnostics were never part of the response. Discard them without storing
                // an unbounded file or leaving a second pipe that can block a chatty child.
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /** True when the launcher's executable is llama-completion (any directory, optional .exe). */
    static boolean completionTool(List<String> launcher) {
        Path name = Path.of(launcher.get(launcher.size() - 1)).getFileName();
        String file = name == null ? "" : name.toString().toLowerCase(java.util.Locale.ROOT);
        return file.equals("llama-completion") || file.equals("llama-completion.exe");
    }

    /** Decode only after removing an exactly recognized byte-truncated chat UI echo. */
    static String reply(byte[] raw, String prompt, boolean completion) throws CharacterCodingException {
        byte[] framed = completion ? null : truncatedChatReply(raw, prompt.getBytes(StandardCharsets.UTF_8));
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(framed == null ? raw : framed)).toString();
        if (framed == null) return reply(text, prompt, completion);
        text = normalizeUi(text);
        if (text.endsWith(CHAT_EXIT)) text = text.substring(0, text.length() - CHAT_EXIT.length());
        return cleanReply(text);
    }

    private static byte[] truncatedChatReply(byte[] raw, byte[] prompt) {
        if (prompt.length <= CHAT_ECHO_BYTES) return null;
        // The pinned chat UI displays the first 500 bytes, which can split UTF-8.
        // ISO-8859-1 is a lossless byte view here, never a model-text decoder.
        String byteView = new String(raw, StandardCharsets.ISO_8859_1);
        String text = normalizeUi(byteView);
        String echo = normalizeUi("\n" + ECHO_MARK
                + new String(prompt, 0, CHAT_ECHO_BYTES, StandardCharsets.ISO_8859_1)
                + " ... (truncated)\n");
        int at = text.indexOf(echo);
        if (at < 0 || !chatUiHeader(text, at)) return null;
        // Preserve the original candidate bytes. Removing ANSI before strict UTF-8
        // decoding could otherwise join invalid byte fragments into a valid character.
        return Arrays.copyOfRange(raw, originalUiBoundary(byteView, at + echo.length()), raw.length);
    }

    /** Map an ANSI/CRLF-normalized UI boundary back to its original byte-view offset. */
    private static int originalUiBoundary(String raw, int normalizedOffset) {
        Matcher ansi = ANSI_ESCAPE.matcher(raw);
        boolean control = ansi.find();
        int visible = 0;
        for (int at = 0; at < raw.length();) {
            if (control && at == ansi.start()) {
                at = ansi.end();
                control = ansi.find();
                continue;
            }
            char value = raw.charAt(at++);
            if (value == '\r') {
                // ANSI is removed before CRLF normalization, including controls
                // between a carriage return and its following line feed.
                while (control && at == ansi.start()) {
                    at = ansi.end();
                    control = ansi.find();
                }
                if (at < raw.length() && raw.charAt(at) == '\n') at++;
            }
            if (++visible == normalizedOffset) return at;
        }
        throw new IllegalArgumentException("normalized UI boundary is outside native stdout");
    }

    private static String normalizeUi(String text) {
        return ANSI_ESCAPE.matcher(text).replaceAll("").replace("\r\n", "\n");
    }

    /**
     * The model's reply from raw stdout: ANSI escapes removed, CRLF normalised, everything up to and including
     * the chat CLI's echoed prompt ("> " + prompt) dropped, and llama.cpp's trailing "[end of text]" removed.
     * Output without an echo (llama-completion) keeps all of its text.
     */
    static String reply(String raw, String prompt) {
        return reply(raw, prompt, false);
    }

    static String reply(String raw, String prompt, boolean completion) {
        String text = normalizeUi(raw);
        String echo = ECHO_MARK + prompt.replace("\r\n", "\n").strip();
        int at = text.indexOf(echo); // the first echo: a reply that quotes the prompt keeps its quote
        // The current chat CLI prints this terminal delimiter outside generation.
        // Require its ordered UI header before the request echo, not just words
        // at the end of model text. Completion tools and unframed text keep it.
        if (!completion && at >= 0 && text.endsWith(CHAT_EXIT)
                && text.length() - CHAT_EXIT.length() >= at + echo.length() && chatUiHeader(text, at)) {
            text = text.substring(0, text.length() - CHAT_EXIT.length());
        }
        if (at >= 0) text = text.substring(at + echo.length());
        return cleanReply(text);
    }

    private static String cleanReply(String text) {
        text = text.strip();
        if (text.endsWith(END_OF_TEXT)) text = text.substring(0, text.length() - END_OF_TEXT.length());
        return text.strip();
    }

    private static boolean chatUiHeader(String text, int echo) {
        int build = text.indexOf("\nbuild      : ");
        int model = text.indexOf("\nmodel      : ", build + 1);
        int modalities = text.indexOf("\nmodalities : ", model + 1);
        int commands = text.indexOf("\navailable commands:\n  /exit or Ctrl+C     stop or exit\n", modalities + 1);
        return build >= 0 && model > build && modalities > model && commands > modalities && commands < echo;
    }

    private static byte[] awaitProcess(Process process, long deadline)
            throws IOException, InterruptedException, TimeoutException {
        process.getOutputStream().close(); // One prompt file is the complete request: stdin is EOF.
        InputStream input = process.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        boolean eof = false;
        try {
            while (true) {
                checkInterrupted();
                long nanos = remaining(deadline);
                int available = eof ? 0 : input.available();
                if (available > 0) {
                    // A single reader consumes only available bytes. This avoids a blocking
                    // read/close race and a stranded reader thread when a child is cancelled.
                    int limit = (int) Math.min(chunk.length, MAX_STDOUT_BYTES - output.size() + 1);
                    int count = input.read(chunk, 0, Math.min(available, limit));
                    if (count < 0) eof = true;
                    else {
                        if (count > MAX_STDOUT_BYTES - output.size()) {
                            throw new IOException("llama.cpp CLI output exceeds bounded limit");
                        }
                        output.write(chunk, 0, count);
                    }
                    continue;
                }
                if (!process.isAlive()) {
                    // The child may have written its final bytes after the first availability
                    // sample and before exit was observed. Drain that stable post-exit tail.
                    if (!eof && input.available() > 0) continue;
                    break;
                }
                process.waitFor(Math.min(nanos, OUTPUT_POLL_NANOS), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (process.exitValue() != 0) {
            throw new IOException("llama.cpp CLI exited with status " + process.exitValue());
        }
        return output.toByteArray();
    }

    private static long remaining(long deadline) throws TimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException("model inference exceeded configured deadline");
        return nanos;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("model inference interrupted");
    }

    private static void stopProcess(Process process) throws IOException {
        if (process.isAlive()) {
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
        }
        // Termination is asynchronous. Wait for the owned child before deleting its prompt.
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
        if (process.isAlive()) throw new IOException("llama.cpp CLI did not terminate within cleanup deadline");
        // Child-process trees require a separate operating-system-specific containment policy.
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
