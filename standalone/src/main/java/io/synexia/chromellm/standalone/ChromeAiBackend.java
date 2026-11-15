// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Explicit adapter to the installed Chrome Prompt API through an owned browser.
 * Construction is lazy. Status never provisions a model. A timeout, interruption
 * or close retires the browser; a new backend is required after retirement.
 */
public final class ChromeAiBackend implements ModelBackend {
    private static final int MAX_PROMPT_BYTES = 1024 * 1024;
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final Set<String> STATES =
            Set.of("unsupported", "unavailable", "downloadable", "downloading", "available");
    private final SessionFactory factory;
    private final boolean allowDownload;
    private final Semaphore admission = new Semaphore(16);
    private final ReentrantLock operation = new ReentrantLock(true);
    private final AtomicLong ids = new AtomicLong();
    private final Object state = new Object();
    private Thread openingThread;
    private volatile Session session;
    private volatile String activeRequest;
    private volatile boolean closed;

    /**
     * @param executable an installed Chrome executable, never a shell command
     * @param profile a dedicated persistent agent profile, checked and locked at launch
     * @param headless whether to hide browser windows
     * @param allowDownload explicitly permits Chrome's own model preparation
     */
    public ChromeAiBackend(Path executable, Path profile, boolean headless, boolean allowDownload) {
        Path binary = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
        Path directory = Objects.requireNonNull(profile, "profile").toAbsolutePath().normalize();
        this.factory = deadline -> BrowserSession.open(binary, directory, headless, deadline);
        this.allowDownload = allowDownload;
    }

    /** Internal process boundary, shared by the provider policy and its runtime adapter. */
    ChromeAiBackend(SessionFactory factory, boolean allowDownload) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.allowDownload = allowDownload;
    }

    @Override public String id() { return "chrome-built-in-ai"; }

    /** Starts the owned browser if necessary, but does not create an AI session. */
    public Map<String, Object> status(Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        return operate(deadline(timeout), (current, end) -> decorated(current.status(end), current));
    }

    /** Explicit model preparation; Chrome checks activation, hardware, policy and availability. */
    public Map<String, Object> prepare(Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        return operate(deadline(timeout), (current, end) -> {
            Map<String, Object> result = current.prepare(allowDownload, end);
            requireSuccess(result);
            if (!"available".equals(availability(result))) throw new IOException("Chrome model is not ready");
            return decorated(result, current);
        });
    }

    @Override public ModelResponse infer(ModelRequest request)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(request, "request");
        long end = deadline(request.timeout());
        byte[] prompt = StdioProtocol.encode(request.prompt(), MAX_PROMPT_BYTES);
        String hash = sha256(prompt);
        return operate(end, (current, deadline) -> {
            String readiness = availability(current.status(deadline));
            if (!"available".equals(readiness)) {
                if (!allowDownload || readiness.equals("unavailable") || readiness.equals("unsupported")) {
                    throw new IOException("Chrome model preparation required: " + readiness);
                }
                Map<String, Object> prepared = current.prepare(true, deadline);
                requireSuccess(prepared);
                if (!"available".equals(availability(prepared))) {
                    throw new IOException("Chrome model is not ready");
                }
            }
            long number = ids.incrementAndGet();
            if (number <= 0) throw new IOException("Chrome request identity exhausted");
            String identity = Long.toString(number);
            activeRequest = identity;
            Map<String, Object> answer = current.infer(Map.of(
                    "id", identity, "prompt", request.prompt(), "maxTokens", request.maxTokens(),
                    "maxOutputBytes", MAX_OUTPUT_BYTES,
                    "timeoutMillis", Math.max(1, TimeUnit.NANOSECONDS.toMillis(ChromeDevTools.remaining(deadline))),
                    "allowDownload", false), deadline);
            requireSuccess(answer);
            if (!(answer.get("text") instanceof String text) || text.isBlank()) {
                throw new IOException("Chrome model returned empty or non-text output");
            }
            int measured = integer(answer.get("measuredOutputTokens"));
            if (measured < 0 || measured > request.maxTokens()) {
                throw new IOException("Chrome response exceeds the measured token budget");
            }
            StdioProtocol.encode(text, MAX_OUTPUT_BYTES);
            return new ModelResponse(text, id(), hash);
        });
    }

    private <T> T operate(long deadline, Operation<T> work)
            throws IOException, InterruptedException, TimeoutException {
        if (closed) throw new IOException("Chrome backend is retired");
        if (!admission.tryAcquire()) throw new IOException("Chrome inference request capacity exceeded");
        boolean locked = false;
        try {
            if (!operation.tryLock(ChromeDevTools.remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("Chrome request expired while waiting");
            }
            locked = true;
            if (closed) throw new IOException("Chrome backend is retired");
            Session current = session;
            if (current == null) {
                synchronized (state) {
                    if (closed) throw new IOException("Chrome backend is retired");
                    openingThread = Thread.currentThread();
                }
                try {
                    Session opened = factory.open(deadline);
                    synchronized (state) {
                        openingThread = null;
                        if (closed) {
                            opened.close();
                            throw new IOException("Chrome backend closed during launch");
                        }
                        session = opened;
                        current = opened;
                    }
                } finally {
                    synchronized (state) { openingThread = null; }
                }
            }
            T result = work.run(current, deadline);
            ChromeDevTools.remaining(deadline);
            if (closed) throw new IOException("Chrome backend closed during operation");
            return result;
        } catch (TimeoutException timeout) {
            if (locked) close();
            throw timeout;
        } catch (InterruptedException interrupted) {
            if (locked) close();
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (locked) {
                activeRequest = null;
                operation.unlock();
            }
            admission.release();
        }
    }

    private Map<String, Object> decorated(Map<String, Object> status, Session current)
            throws IOException, TimeoutException {
        availability(status);
        Map<String, Object> result = new LinkedHashMap<>(status);
        result.put("backendId", id());
        result.put("browserVersion", current.version());
        result.put("allowDownload", allowDownload);
        return java.util.Collections.unmodifiableMap(result);
    }

    private String availability(Map<String, Object> status) throws IOException, TimeoutException {
        requireHealthy(status);
        Object value = status.get("availability");
        if (!(value instanceof String text) || !STATES.contains(text)) {
            throw new IOException("Chrome returned an unknown availability state");
        }
        return text;
    }

    private void requireSuccess(Map<String, Object> response) throws IOException, TimeoutException {
        requireHealthy(response);
        if (Boolean.TRUE.equals(response.get("ok"))) return;
        String reason = response.get("error") instanceof String value
                && value.matches("[A-Z][A-Z0-9_]{0,79}") ? value : "OPERATION_FAILED";
        throw new IOException("Chrome AI " + reason);
    }

    private void requireHealthy(Map<String, Object> response) throws IOException, TimeoutException {
        if ("TIMEOUT".equals(response.get("error"))) throw new TimeoutException("Chrome AI TIMEOUT");
        if ("SESSION_CLEANUP_FAILED".equals(response.get("error"))) {
            close();
            throw new IOException("Chrome AI SESSION_CLEANUP_FAILED");
        }
    }

    private static int integer(Object value) throws IOException {
        try {
            if (value instanceof BigDecimal number) return number.intValueExact();
            if (value instanceof Integer number) return number;
            if (value instanceof Long number) return Math.toIntExact(number);
        } catch (ArithmeticException error) {
            throw new IOException("invalid Chrome token measurement", error);
        }
        throw new IOException("Chrome response token measurement is missing");
    }

    private static long deadline(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("timeout must be in (0, 1 hour]");
        }
        return System.nanoTime() + timeout.toNanos();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @Override public void close() {
        Session owned;
        String request;
        synchronized (state) {
            closed = true;
            owned = session;
            session = null;
            request = activeRequest;
            // Keep interruption inside the ownership lock so a completed launch
            // cannot return its caller thread to unrelated work before this signal.
            if (openingThread != null && openingThread != Thread.currentThread()) openingThread.interrupt();
        }
        if (owned == null) return;
        try {
            if (request != null && !Thread.currentThread().isInterrupted()) {
                owned.cancel(request, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100));
            }
        } catch (IOException | TimeoutException | RuntimeException ignored) {
            // Retiring the owned process is the stronger boundary if cooperative cancellation fails.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            owned.close();
        }
    }

    @FunctionalInterface interface SessionFactory {
        Session open(long deadline) throws IOException, InterruptedException, TimeoutException;
    }

    interface Session extends AutoCloseable {
        Map<String, Object> status(long deadline) throws IOException, InterruptedException, TimeoutException;
        Map<String, Object> prepare(boolean allowDownload, long deadline)
                throws IOException, InterruptedException, TimeoutException;
        Map<String, Object> infer(Map<String, Object> request, long deadline)
                throws IOException, InterruptedException, TimeoutException;
        void cancel(String id, long deadline) throws IOException, InterruptedException, TimeoutException;
        String version();
        @Override void close();
    }

    @FunctionalInterface private interface Operation<T> {
        T run(Session session, long deadline) throws IOException, InterruptedException, TimeoutException;
    }

    private record BrowserSession(ChromeProcess process, ChromeDevTools transport, String objectId)
            implements Session {
        static BrowserSession open(Path binary, Path profile, boolean headless, long deadline)
                throws IOException, InterruptedException, TimeoutException {
            ChromeProcess process = ChromeProcess.start(binary, profile, headless, deadline);
            ChromeDevTools connection = null;
            try {
                connection = ChromeDevTools.connect(process.endpoint(), deadline);
                while (!Boolean.TRUE.equals(connection.evaluate(
                        "typeof globalThis.chromeLlm === 'object'", false, deadline))) {
                    TimeUnit.NANOSECONDS.sleep(Math.min(ChromeDevTools.remaining(deadline),
                            TimeUnit.MILLISECONDS.toNanos(25)));
                }
                Map<String, Object> answer = connection.command("Runtime.evaluate",
                        Map.of("expression", "globalThis.chromeLlm", "returnByValue", false), deadline);
                Map<String, Object> remote = ChromeDevTools.object(answer.get("result"));
                if (!(remote.get("objectId") instanceof String identity) || identity.isBlank()) {
                    throw new IOException("Chrome bridge object is unavailable");
                }
                return new BrowserSession(process, connection, identity);
            } catch (IOException | InterruptedException | TimeoutException | RuntimeException failure) {
                if (connection != null) connection.close();
                process.close();
                throw failure;
            }
        }

        @Override public Map<String, Object> status(long deadline)
                throws IOException, InterruptedException, TimeoutException {
            return ChromeDevTools.object(transport.evaluate("globalThis.chromeLlm.availability()", false, deadline));
        }

        @Override public Map<String, Object> prepare(boolean download, long deadline)
                throws IOException, InterruptedException, TimeoutException {
            // The explicit setup command authorizes this CDP automation gesture.
            // Chrome still enforces API activation, policy and hardware eligibility.
            return ChromeDevTools.object(transport.evaluate(
                    "globalThis.chromeLlm.prepare(" + download + ")", download, deadline));
        }

        @Override public Map<String, Object> infer(Map<String, Object> request, long deadline)
                throws IOException, InterruptedException, TimeoutException {
            return ChromeDevTools.object(transport.invoke(objectId, "infer", request, deadline));
        }

        @Override public void cancel(String id, long deadline)
                throws IOException, InterruptedException, TimeoutException {
            transport.invoke(objectId, "cancel", id, deadline);
        }

        @Override public String version() { return process.version(); }

        @Override public void close() {
            transport.close();
            process.close();
        }
    }
}
