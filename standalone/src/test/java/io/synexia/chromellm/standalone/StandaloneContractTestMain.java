// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.File;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** JDK-only executable contract tests run by Maven verify and plain javac/java. */
public final class StandaloneContractTestMain {
    private static int checks;

    private StandaloneContractTestMain() { }

    public static void main(String[] args) throws Exception {
        reject(() -> new ModelRequest(" ", 1, Duration.ofSeconds(1)));
        reject(() -> new ModelRequest("hi", 0, Duration.ofSeconds(1)));
        reject(() -> new ModelRequest("hi", 1, Duration.ZERO));
        reject(() -> new ModelRequest("hi", 1, Duration.ofHours(2)));
        Path temp = Files.createTempDirectory("chromellm-standalone-test-");
        Path model = temp.resolve("fixture.gguf");
        Throwable failure = null;
        try {
            Files.writeString(model, "not a trained model");
            String java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            String testClasses = Path.of(FakeLlamaCppMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
            List<String> launcher = List.of(java, "-XX:-UsePerfData", "-cp", testClasses, FakeLlamaCppMain.class.getName());
            try (ModelBackend backend = new LlamaCppCliBackend(
                    launcher, model, temp)) {
                ModelResponse result = backend.infer(new ModelRequest("private input", 8, Duration.ofSeconds(10)));
                check(result.text().equals("synthetic reply"));
                check(result.backendId().equals("llama.cpp-cli"));
                check(result.promptSha256().length() == 64);
                onlyModelRemains(temp);
                try {
                    backend.infer(new ModelRequest("fail", 8, Duration.ofSeconds(10)));
                    throw new AssertionError("failed process accepted");
                } catch (IOException expected) {
                    checks++;
                }
                onlyModelRemains(temp);
                try {
                    backend.infer(new ModelRequest("sleep", 8, Duration.ofMillis(150)));
                    throw new AssertionError("deadline not enforced");
                } catch (TimeoutException expected) {
                    checks++;
                }
                onlyModelRemains(temp);
                cleanupFailure(backend, temp, true);
                cleanupFailure(backend, temp, false);
                outputContracts(backend, temp);
                replyContracts();
                truncatedReplyContracts();
            }
            reject(() -> new LlamaCppCliBackend(launcher, model, temp, "--unsafe", 16));
            reject(() -> new LlamaCppCliBackend(launcher, model, temp, "WebGPU0", -2));
            try (ModelBackend gpu = new LlamaCppCliBackend(launcher, model, temp, "WebGPU0", 16)) {
                ModelResponse result = gpu.infer(new ModelRequest("check-webgpu", 8, Duration.ofSeconds(10)));
                check(result.text().equals("synthetic reply"));
                check(result.backendId().equals("llama.cpp-cli"));
            }
            interruptBeforeStart(launcher, model, temp);
            interruptRunningChild(launcher, model, temp);
            onlyModelRemains(temp);
            boundedEncodingMemory(java, testClasses);
            exitTailRace();
        } catch (Exception | Error primary) {
            failure = primary;
            throw primary;
        } finally {
            try {
                deleteFixtureTree(temp);
            } catch (IOException cleanup) {
                if (failure == null) throw cleanup;
                failure.addSuppressed(cleanup);
            }
        }
        TieredInferenceTestMain.main(new String[0]);
        checks += StdioProtocolContractTestMain.run();
        System.out.println("standalone contract checks passed: " + checks);
    }

    private static void outputContracts(ModelBackend backend, Path temp) throws Exception {
        // The child stays alive after writing the excess. A post-exit size check times out.
        try {
            backend.infer(new ModelRequest("stdout-overflow", 8, Duration.ofSeconds(10)));
            throw new AssertionError("excess stdout accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("bounded limit"));
        }
        onlyModelRemains(temp);
        ModelResponse full = backend.infer(new ModelRequest("stdout-limit", 8, Duration.ofSeconds(10)));
        check(full.text().length() == 4 * 1024 * 1024 && full.text().chars().allMatch(c -> c == 'x'),
                "exact stdout bound was truncated or corrupted");
        onlyModelRemains(temp);
        String stderrText = backend.infer(new ModelRequest("stderr-flood", 8, Duration.ofSeconds(10))).text();
        boolean stderrClean = stderrText.equals("synthetic reply");
        check(stderrClean, "stderr blocked or contaminated the answer"
                + (stderrClean ? "" : responseDiagnostic(stderrText)));
        onlyModelRemains(temp);
        check(backend.infer(new ModelRequest("stdin-eof", 8, Duration.ofSeconds(10)))
                .text().equals("synthetic reply"), "stdin was left open");
        check(backend.infer(new ModelRequest("unicode-output", 8, Duration.ofSeconds(10)))
                .text().equals("नमस्ते 🦙"), "split UTF-8 output changed");
        for (String prompt : List.of("empty-output", "invalid-output", "truncated-output")) {
            try {
                backend.infer(new ModelRequest(prompt, 8, Duration.ofSeconds(10)));
                throw new AssertionError("invalid output accepted: " + prompt);
            } catch (IOException expected) {
                checks++;
            }
            onlyModelRemains(temp);
        }
        try {
            backend.infer(new ModelRequest("stream-then-sleep", 8, Duration.ofMillis(500)));
            throw new AssertionError("partial output bypassed the deadline");
        } catch (TimeoutException expected) {
            checks++;
        }
        onlyModelRemains(temp);
        try {
            backend.infer(new ModelRequest("expired", 8, Duration.ofNanos(1)));
            throw new AssertionError("expired invocation was launched");
        } catch (TimeoutException expected) {
            checks++;
        }
        onlyModelRemains(temp);
        try {
            backend.infer(new ModelRequest("bad-\ud800", 8, Duration.ofSeconds(10)));
            throw new AssertionError("malformed UTF-16 prompt accepted");
        } catch (IOException expected) {
            checks++;
        }
        onlyModelRemains(temp);
        try {
            backend.infer(new ModelRequest("x".repeat(StdioProtocol.MAX_PROMPT_BYTES + 1),
                    8, Duration.ofSeconds(10)));
            throw new AssertionError("oversized prompt accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("bounded limit"));
        }
        onlyModelRemains(temp);
    }

    /** A bounded, inert byte preview for the synthetic child's unexpected stdout. */
    private static String responseDiagnostic(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder preview = new StringBuilder();
        int limit = Math.min(bytes.length, 128);
        for (int i = 0; i < limit; i++) {
            int value = bytes[i] & 0xff;
            // Escape control bytes, non-ASCII, quotes, backslashes and workflow-command delimiters.
            if (value >= 0x20 && value <= 0x7e && value != '"' && value != '\\' && value != ':') {
                preview.append((char) value);
            } else {
                preview.append("\\x").append(Character.forDigit(value >>> 4, 16))
                        .append(Character.forDigit(value & 0xf, 16));
            }
        }
        if (bytes.length > limit) preview.append("...");
        return " (actual UTF-8 bytes=" + bytes.length + ", preview=\"" + preview + "\")";
    }

    /** Final bytes arrive after the empty-pipe sample and immediately before exit is observed. */
    private static void exitTailRace() throws Exception {
        String expected = "last response bytes नमस्ते 🦙";
        class ExitingProcess extends Process {
            private boolean observedExit;
            private final ByteArrayInputStream stdout = new ByteArrayInputStream(
                    expected.getBytes(StandardCharsets.UTF_8)) {
                @Override public synchronized int available() {
                    return observedExit ? super.available() : 0;
                }
            };
            @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
            @Override public InputStream getInputStream() { return stdout; }
            @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            @Override public int waitFor() { observedExit = true; return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { observedExit = true; }
            @Override public boolean isAlive() { observedExit = true; return false; }
        }
        var await = LlamaCppCliBackend.class.getDeclaredMethod("awaitProcess", Process.class, long.class);
        await.setAccessible(true);
        byte[] bytes = (byte[]) await.invoke(null, new ExitingProcess(),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
        check(new String(bytes, StandardCharsets.UTF_8).equals(expected), "final stdout lost at child exit");
    }

    private static void boundedEncodingMemory(String java, String testClasses) throws Exception {
        String classes = Path.of(StdioProtocol.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        Process probe = new ProcessBuilder(java, "-Xmx64m", "-cp", classes + File.pathSeparator + testClasses,
                StdioProtocolContractTestMain.class.getName(), "--bounded-memory")
                .redirectErrorStream(true).start();
        try {
            if (!probe.waitFor(20, TimeUnit.SECONDS)) throw new AssertionError("bounded encoding probe timed out");
            String output = new String(probe.getInputStream().readNBytes(64 * 1024), StandardCharsets.UTF_8);
            check(probe.exitValue() == 0 && output.contains("bounded encoding memory check passed"),
                    "bounded encoding probe failed: " + output);
        } finally {
            if (probe.isAlive()) {
                probe.destroyForcibly();
                probe.waitFor(5, TimeUnit.SECONDS);
            }
            probe.getInputStream().close();
            probe.getErrorStream().close();
            probe.getOutputStream().close();
        }
    }

    private static void cleanupFailure(ModelBackend backend, Path temp, boolean failedProcess) throws Exception {
        String prompt = failedProcess ? "cleanup-failure-exit" : "cleanup-failure-success";
        try {
            backend.infer(new ModelRequest(prompt, 8, Duration.ofSeconds(10)));
            throw new AssertionError("cleanup failure accepted");
        } catch (IOException expected) {
            if (failedProcess) {
                check(expected.getMessage().equals("llama.cpp CLI exited with status 4"),
                        "cleanup replaced the primary inference failure: " + expected);
                check(expected.getSuppressed().length == 1
                        && expected.getSuppressed()[0] instanceof DirectoryNotEmptyException,
                        "cleanup failure was not retained as suppressed");
            } else {
                check(expected instanceof DirectoryNotEmptyException, "cleanup failure was not reported");
            }
        }
        List<Path> leftovers;
        try (var paths = Files.list(temp)) {
            leftovers = paths.filter(path -> !path.getFileName().toString().equals("fixture.gguf")).toList();
        }
        check(leftovers.size() == 1, "cleanup skipped another owned file: " + leftovers);
        Path replacedPrompt = leftovers.getFirst();
        check(replacedPrompt.getFileName().toString().startsWith("chromellm-prompt-")
                && Files.isDirectory(replacedPrompt));
        Files.delete(replacedPrompt.resolve("owned-marker"));
        Files.delete(replacedPrompt);
        onlyModelRemains(temp);
    }

    private static void interruptBeforeStart(List<String> launcher, Path model, Path temp) throws Exception {
        Path scratch = Files.createDirectory(temp.resolve("cancelled-scratch"));
        try (ModelBackend backend = new LlamaCppCliBackend(launcher, model, scratch)) {
            // Removing the validated scratch directory makes any attempted allocation fail.
            // Cancellation must be observed first, without touching files or starting a child.
            Files.delete(scratch);
            Thread.currentThread().interrupt();
            try {
                backend.infer(new ModelRequest("sleep", 8, Duration.ofSeconds(10)));
                throw new AssertionError("interrupted call accepted");
            } catch (InterruptedException expected) {
                check(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
        onlyModelRemains(temp);
    }

    private static void interruptRunningChild(List<String> launcher, Path model, Path temp) throws Exception {
        Path ready = temp.resolve("child-ready");
        List<String> markedLauncher = new ArrayList<>(launcher);
        markedLauncher.addAll(List.of("--ready-file", ready.toString()));
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicBoolean interruptRetained = new AtomicBoolean();
        try (ModelBackend backend = new LlamaCppCliBackend(markedLauncher, model, temp)) {
            Thread worker = new Thread(() -> {
                try {
                    backend.infer(new ModelRequest("sleep", 8, Duration.ofSeconds(30)));
                    outcome.set(new AssertionError("interrupted call accepted"));
                } catch (Throwable result) {
                    outcome.set(result);
                    interruptRetained.set(Thread.currentThread().isInterrupted());
                }
            }, "standalone-interruption-test");
            worker.setDaemon(true);
            worker.start();
            Throwable failure = null;
            ProcessHandle child = null;
            try {
                child = awaitReadyChild(ready, worker, outcome);
                check(child.isAlive());
                worker.interrupt();
                worker.join(10_000);
                check(!worker.isAlive());
                if (!(outcome.get() instanceof InterruptedException)) {
                    throw new AssertionError("running call did not report interruption", outcome.get());
                }
                checks++;
                check(interruptRetained.get());
                check(!child.isAlive());
            } catch (Exception | Error primary) {
                failure = primary;
                throw primary;
            } finally {
                try {
                    worker.interrupt();
                    worker.join(7_000);
                    if (child != null && child.isAlive()) {
                        child.destroyForcibly();
                        child.onExit().get(5, TimeUnit.SECONDS);
                        worker.join(1_000);
                    }
                    if (worker.isAlive()) throw new AssertionError("inference worker failed to terminate");
                    Files.deleteIfExists(ready);
                } catch (Exception | Error cleanup) {
                    if (failure == null) throw cleanup;
                    failure.addSuppressed(cleanup);
                }
            }
        }
    }

    private static ProcessHandle awaitReadyChild(Path ready, Thread worker,
                                                 AtomicReference<Throwable> outcome) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(ready)) {
                String marker = Files.readString(ready);
                // The newline is a completion sentinel; never parse a partially written PID.
                if (marker.endsWith("\n")) {
                    return ProcessHandle.of(Long.parseLong(marker.strip()))
                            .orElseThrow(() -> new AssertionError("ready child already exited"));
                }
            }
            if (!worker.isAlive()) throw new AssertionError("inference ended before readiness", outcome.get());
            Thread.sleep(10);
        }
        throw new AssertionError("child readiness deadline exceeded");
    }

    private static void onlyModelRemains(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            List<String> names = files.map(path -> path.getFileName().toString()).sorted().toList();
            check(names.equals(List.of("fixture.gguf")), "owned inference files remain: " + names);
        }
    }

    private static void deleteFixtureTree(Path directory) throws IOException {
        List<Path> paths;
        try (var walk = Files.walk(directory)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        IOException failure = null;
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }

    /** The chat CLI's banner, colours and prompt echo never reach the reply; llama-completion's text is kept. */
    private static void replyContracts() {
        String prompt = "Maven build failed:\nGive one short fix.";
        String chatCli = "\r\n\r\nLoading model... \r\n\r\n\u2584\u2584 \u2584\u2584\r\nbuild      : b12465\r\n\r\n"
                + "available commands:\r\n  /exit or Ctrl+C     stop or exit\r\n\r\n\u001B[1m\u001B[32m\r\n"
                + "> Maven build failed:\r\nGive one short fix.\r\n\u001B[0mRename Strng to String.\r\n\r\n";
        check(LlamaCppCliBackend.reply(chatCli, prompt).equals("Rename Strng to String."), "chat CLI reply");
        check(LlamaCppCliBackend.reply("\r\n\r\nRename it.\r\n [end of text]\r\n\r\n", prompt).equals("Rename it."),
                "completion reply");
        check(LlamaCppCliBackend.reply("> a quoted line\nanswer", prompt).equals("> a quoted line\nanswer"),
                "a reply without an echo keeps its quote");
        String quoting = "> " + prompt + "\nYou wrote:\n> " + prompt + "\nFix it.";
        check(LlamaCppCliBackend.reply(quoting, prompt).equals("You wrote:\n> " + prompt + "\nFix it."),
                "only the first echo is dropped");
        check(LlamaCppCliBackend.completionTool(List.of("C:/llama/b12465/llama-completion.exe")), "completion exe");
        check(LlamaCppCliBackend.completionTool(List.of("/opt/llama/llama-completion")), "completion binary");
        check(!LlamaCppCliBackend.completionTool(List.of("/opt/llama/llama-cli")), "chat CLI");
        check(!LlamaCppCliBackend.completionTool(List.of("java", "-cp", "x", "Fake")), "test launcher");
        String frame = "\nbuild      : pinned\nmodel      : fixture\nmodalities : text\n"
                + "\navailable commands:\n  /exit or Ctrl+C     stop or exit\n\n> " + prompt + "\n";
        String footer = "\n\nExiting...\n";
        check(LlamaCppCliBackend.reply(frame + "Fix it." + footer, prompt).equals("Fix it."),
                "framed chat CLI exit footer is not model output");
        check(LlamaCppCliBackend.reply((frame + "Fix it." + footer).replace("\n", "\r\n"), prompt)
                .equals("Fix it."), "framed Windows CLI exit footer");
        check(LlamaCppCliBackend.reply(frame + "Fix it.\n[end of text]" + footer, prompt).equals("Fix it."),
                "end-of-text marker before framed CLI footer");
        check(LlamaCppCliBackend.reply(frame + "\u001B[32mFix it.\u001B[0m" + footer, prompt).equals("Fix it."),
                "ANSI formatting does not hide framed terminal delimiter");
        check(LlamaCppCliBackend.reply(frame + footer, prompt).isEmpty(), "UI-only output is empty");
        check(LlamaCppCliBackend.reply("Answer" + footer, prompt).equals("Answer\n\nExiting..."),
                "unframed model suffix is retained");
        check(LlamaCppCliBackend.reply("> " + prompt + "\nAnswer" + footer, prompt)
                .equals("Answer\n\nExiting..."), "echo alone does not establish CLI terminal framing");
        check(LlamaCppCliBackend.reply(frame.replace("model      : fixture\n", "") + "Answer" + footer, prompt)
                .equals("Answer\n\nExiting..."), "incomplete UI header does not establish terminal framing");
        check(LlamaCppCliBackend.reply(frame + "Answer" + footer, prompt, true).equals("Answer\n\nExiting..."),
                "completion output retains an otherwise matching suffix");
        check(LlamaCppCliBackend.reply(frame + "Say Exiting... here." + footer, prompt).equals("Say Exiting... here."),
                "inline model text is preserved");
        check(LlamaCppCliBackend.reply(frame + "Answer\n\nExiting..." + footer, prompt)
                .equals("Answer\n\nExiting..."), "only terminal UI footer is removed, preserving model-authored suffix");
        check(LlamaCppCliBackend.reply(frame + "Answer\nExiting...\n", prompt).equals("Answer\nExiting..."),
                "single-line model suffix is not the terminal delimiter");
        check(LlamaCppCliBackend.reply(frame + "Answer\n\nExiting...", prompt).equals("Answer\n\nExiting..."),
                "unterminated candidate suffix is preserved");
        String terminalPrompt = prompt + "\n\nExiting...";
        String echoOnly = frame.substring(0, frame.indexOf("> ")) + "> " + terminalPrompt + "\n";
        check(LlamaCppCliBackend.reply(echoOnly, terminalPrompt).isEmpty(),
                "a terminal phrase inside the echoed prompt is not a footer");
    }

    /** Exact native UI byte framing must never relax UTF-8 validation of candidate text. */
    private static void truncatedReplyContracts() throws Exception {
        String header = "\nbuild      : pinned\nmodel      : fixture\nmodalities : text\n"
                + "\navailable commands:\n  /exit or Ctrl+C     stop or exit\n";
        String footer = "\n\nExiting...\n";
        String longPrompt = "x".repeat(501);
        byte[] answer = "Review the failing goal.".getBytes(StandardCharsets.UTF_8);
        byte[] transcript = chatTranscript(header, longPrompt, answer, footer);
        check(LlamaCppCliBackend.reply(transcript, longPrompt, false).equals("Review the failing goal."),
                "501-byte prompt uses the exact native truncation frame");
        String boundary = "x".repeat(500);
        check(LlamaCppCliBackend.reply(chatTranscript(header, boundary, answer, footer), boundary, false)
                .equals("Review the failing goal."), "500-byte prompt retains the full-echo boundary");
        check(LlamaCppCliBackend.reply((header + "\n> " + longPrompt + "\nAnswer" + footer)
                .getBytes(StandardCharsets.UTF_8), longPrompt, false).equals("Answer"),
                "a CLI with a full long echo keeps its existing behavior");
        String unicode = "x".repeat(499) + "🦙 finish";
        byte[] unicodeTranscript = chatTranscript(header, unicode, "नमस्ते 🦙".getBytes(StandardCharsets.UTF_8), footer);
        check(LlamaCppCliBackend.reply(unicodeTranscript, unicode, false).equals("नमस्ते 🦙"),
                "a proven UI echo can split UTF-8 while the candidate remains strictly decoded");
        String threeByte = "x".repeat(498) + "€ finish";
        check(LlamaCppCliBackend.reply(chatTranscript(header, threeByte, answer, footer), threeByte, false)
                .equals("Review the failing goal."), "two bytes of a three-byte echo character are not candidate bytes");
        String multiByte = "é".repeat(251);
        check(LlamaCppCliBackend.reply(chatTranscript(header, multiByte, answer, footer), multiByte, false)
                .equals("Review the failing goal."), "truncation counts UTF-8 bytes rather than Java characters");
        String spaced = " \t" + "x".repeat(500) + "\n";
        check(LlamaCppCliBackend.reply(chatTranscript(header, spaced, answer, footer), spaced, false)
                .equals("Review the failing goal."), "byte-derived echo preserves prompt boundary whitespace");
        String multiline = "line\n".repeat(101);
        byte[] windows = new String(chatTranscript(header, multiline, answer, footer), StandardCharsets.UTF_8)
                .replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        check(LlamaCppCliBackend.reply(windows, multiline, false).equals("Review the failing goal."),
                "Windows CRLF output preserves the byte-derived prompt echo");
        byte[] coloredWindows = new String(transcript, StandardCharsets.UTF_8)
                .replace("\n", "\r\u001B[0m\n").getBytes(StandardCharsets.UTF_8);
        check(LlamaCppCliBackend.reply(coloredWindows, longPrompt, false).equals("Review the failing goal."),
                "controls between CR and LF preserve the original candidate boundary");
        String crlfPrompt = "line\r\n".repeat(100);
        check(LlamaCppCliBackend.reply(chatTranscript(header, crlfPrompt, answer, footer), crlfPrompt, false)
                .equals("Review the failing goal."), "CRLF input is counted before UI normalization");
        check(LlamaCppCliBackend.reply(chatTranscript("\u001B[32m" + header + "\u001B[0m", longPrompt,
                "\u001B[1mAnswer\u001B[0m".getBytes(StandardCharsets.UTF_8), footer), longPrompt, false)
                .equals("Answer"), "ANSI controls retain framed truncation recognition");
        String quoted = "You wrote:\n> " + longPrompt + "\nKeep this quotation.";
        check(LlamaCppCliBackend.reply(chatTranscript(header, longPrompt, quoted.getBytes(StandardCharsets.UTF_8), footer),
                longPrompt, false).equals(quoted), "model-authored full prompt quote survives one UI echo removal");
        check(LlamaCppCliBackend.reply(chatTranscript(header, longPrompt,
                "Answer\n\nExiting...".getBytes(StandardCharsets.UTF_8), footer), longPrompt, false)
                .equals("Answer\n\nExiting..."), "truncated UI removes exactly one terminal footer");
        check(LlamaCppCliBackend.reply(chatTranscript(header, longPrompt, answer, ""), longPrompt, false)
                .equals("Review the failing goal."), "recognized echo does not require a footer to preserve text");
        check(LlamaCppCliBackend.reply(chatTranscript(header, longPrompt, answer, "\n\nExiting..."), longPrompt, false)
                .equals("Review the failing goal.\n\nExiting..."), "unterminated model suffix remains model text");
        check(LlamaCppCliBackend.reply(chatTranscript(header, longPrompt, new byte[0], footer), longPrompt, false)
                .isEmpty(), "truncated UI-only output is not a candidate");
        for (byte[] unframed : List.of(chatTranscript("", longPrompt, answer, footer),
                chatTranscript(header.replace("model      : fixture\n", ""), longPrompt, answer, footer))) {
            check(LlamaCppCliBackend.reply(unframed, longPrompt, false)
                    .equals(new String(unframed, StandardCharsets.UTF_8).strip()),
                    "unproven UI frame cannot remove a truncated quotation");
        }
        check(LlamaCppCliBackend.reply(transcript, longPrompt, true)
                .equals(new String(transcript, StandardCharsets.UTF_8).strip()),
                "completion output never uses chat truncation framing");
        String mismatch = new String(transcript, StandardCharsets.UTF_8).replace(" ... (truncated)", " ... (shortened)");
        check(LlamaCppCliBackend.reply(mismatch.getBytes(StandardCharsets.UTF_8), longPrompt, false).equals(mismatch.strip()),
                "literal native truncation terminator is required");
        String noNewline = new String(transcript, StandardCharsets.UTF_8).replace(" ... (truncated)\n", " ... (truncated)");
        check(LlamaCppCliBackend.reply(noNewline.getBytes(StandardCharsets.UTF_8), longPrompt, false).equals(noNewline.strip()),
                "truncation terminator must end at its native newline boundary");
        check(LlamaCppCliBackend.reply(transcript, "y" + longPrompt.substring(1), false)
                .equals(new String(transcript, StandardCharsets.UTF_8).strip()), "wrong input prefix is never an echo");
        malformedReply(chatTranscript(header, longPrompt, new byte[] {(byte) 0xff}, footer), longPrompt, false);
        malformedReply(chatTranscript(header, longPrompt, new byte[] {(byte) 0xe2, (byte) 0x82}, footer), longPrompt, false);
        malformedReply(chatTranscript(header, longPrompt,
                new byte[] {(byte) 0xc2, 0x1b, '[', '3', '1', 'm', (byte) 0xa9}, footer), longPrompt, false);
        malformedReply(chatTranscript(header, longPrompt,
                new byte[] {(byte) 0xe2, 0x1b, '[', '0', 'm', (byte) 0x82, (byte) 0xac}, footer), longPrompt, false);
        malformedReply(unicodeTranscript, unicode, true);
        malformedReply(chatTranscript("", unicode, answer, footer), unicode, false);
        malformedReply(unicodeTranscript, "y" + unicode.substring(1), false);
    }

    /** Reference transcript for pinned llama.cpp cli-ui.h user_turn.echo. */
    private static byte[] chatTranscript(String header, String prompt, byte[] candidate, String footer) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes((header + "\n> ").getBytes(StandardCharsets.UTF_8));
        byte[] input = prompt.getBytes(StandardCharsets.UTF_8);
        output.write(input, 0, Math.min(input.length, 500));
        output.writeBytes((input.length > 500 ? " ... (truncated)\n" : "\n").getBytes(StandardCharsets.UTF_8));
        output.writeBytes(candidate);
        output.writeBytes(footer.getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private static void malformedReply(byte[] transcript, String prompt, boolean completion) throws Exception {
        try {
            LlamaCppCliBackend.reply(transcript, prompt, completion);
            throw new AssertionError("malformed candidate or unproven UI bytes were accepted");
        } catch (CharacterCodingException expected) {
            checks++;
        }
    }

    private static void check(boolean condition) {
        check(condition, "contract check " + (checks + 1));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void reject(Runnable callback) {
        try {
            callback.run();
            throw new AssertionError("invalid request accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }
}
