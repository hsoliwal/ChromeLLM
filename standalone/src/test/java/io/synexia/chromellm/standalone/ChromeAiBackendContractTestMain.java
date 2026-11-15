// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Backend policy and ownership contracts; the external browser is a narrow port. */
public final class ChromeAiBackendContractTestMain {
    private static final String HELLO_SHA = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    private ChromeAiBackendContractTestMain() { }

    public static void main(String[] args) throws Exception {
        constructionAndStatusArePassive();
        responseRetainsExactPromptIdentity();
        coldInferenceRequiresExplicitPreparation();
        boundsAreCheckedBeforeLaunch();
        untrustedResultsAreChecked();
        requestDeadlineRetiresUncertainSession();
        queuedDeadlineDoesNotRetireActiveWork();
        closeCancelsActiveWork();
        cancellationFailureDoesNotMaskDeadline();
        closeInterruptsStartup();
        lateStartupCannotEscapeOwnership();
        browserReportedTimeoutRetiresSession();
        failedNativeCleanupRetiresSession();
        System.out.println("Chrome AI backend contracts: 13 passed");
    }

    private static void constructionAndStatusArePassive() throws Exception {
        Fixture bridge = new Fixture();
        AtomicInteger starts = new AtomicInteger();
        try (ChromeAiBackend backend = new ChromeAiBackend(deadline -> {
            starts.incrementAndGet(); return bridge;
        }, false)) {
            check(starts.get() == 0, "construction eagerly launched browser");
            check("available".equals(backend.status(Duration.ofSeconds(2)).get("availability")),
                    "status changed readiness");
            check(backend.status(Duration.ofSeconds(2)).containsKey("downloadProgress"),
                    "nullable readiness metadata was dropped");
            check(starts.get() == 1 && bridge.preparations == 0 && bridge.inferences == 0,
                    "status prepared or inferred");
        }
        check(bridge.closed, "browser ownership leaked");
    }

    private static void responseRetainsExactPromptIdentity() throws Exception {
        Fixture bridge = new Fixture();
        try (ChromeAiBackend backend = new ChromeAiBackend(deadline -> bridge, false)) {
            ModelResponse response = backend.infer(request("hello", 10));
            check("fixture output".equals(response.text()), "output changed");
            check("chrome-built-in-ai".equals(response.backendId()), "provider identity changed");
            check(HELLO_SHA.equals(response.promptSha256()), "receipt not tied to exact prompt");
            check("hello".equals(bridge.request.get("prompt")), "request prompt changed");
            check(Boolean.FALSE.equals(bridge.request.get("allowDownload")), "inference downloaded implicitly");
        }
    }

    private static void coldInferenceRequiresExplicitPreparation() throws Exception {
        Fixture cold = new Fixture();
        cold.availability = "downloadable";
        try (ChromeAiBackend backend = new ChromeAiBackend(deadline -> cold, false)) {
            expect(IOException.class, () -> backend.infer(request("hello", 10)));
            check(cold.preparations == 0 && cold.inferences == 0, "cold inference performed work");
        }
        Fixture optedIn = new Fixture();
        optedIn.availability = "downloadable";
        try (ChromeAiBackend backend = new ChromeAiBackend(deadline -> optedIn, true)) {
            check("fixture output".equals(backend.infer(request("hello", 10)).text()), "prepared inference failed");
            check(optedIn.preparations == 1 && optedIn.inferences == 1, "preparation lifecycle duplicated");
        }
    }

    private static void boundsAreCheckedBeforeLaunch() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        try (ChromeAiBackend backend = new ChromeAiBackend(deadline -> {
            starts.incrementAndGet(); return new Fixture();
        }, false)) {
            expect(IOException.class, () -> backend.infer(request("x".repeat(1024 * 1024 + 1), 10)));
            expect(IOException.class, () -> backend.infer(request("\uD800", 10)));
            check(starts.get() == 0, "invalid input launched a browser");
        }
    }

    private static void untrustedResultsAreChecked() throws Exception {
        for (Map<String, Object> bad : java.util.List.<Map<String, Object>>of(
                Map.of("ok", false, "error", "CONTEXT_OVERFLOW"),
                Map.of("ok", true, "text", "answer", "measuredOutputTokens", BigDecimal.valueOf(11)),
                Map.of("ok", true, "text", "", "measuredOutputTokens", BigDecimal.ONE),
                Map.of("ok", true, "text", "\uD800", "measuredOutputTokens", BigDecimal.ONE),
                Map.of("ok", true, "text", "answer"),
                Map.of("ok", true, "text", "answer", "measuredOutputTokens", new BigDecimal("1.5")))) {
            Fixture bridge = new Fixture();
            bridge.result = bad;
            try (ChromeAiBackend backend = new ChromeAiBackend(deadline -> bridge, false)) {
                expect(IOException.class, () -> backend.infer(request("hello", 10)));
            }
        }
    }

    private static void requestDeadlineRetiresUncertainSession() throws Exception {
        Fixture bridge = new Fixture();
        bridge.stall = true;
        ChromeAiBackend backend = new ChromeAiBackend(deadline -> bridge, false);
        try {
            expect(TimeoutException.class, () -> backend.infer(
                    new ModelRequest("hello", 10, Duration.ofMillis(40))));
            check(bridge.closed, "timed-out native work remained owned");
            expect(IOException.class, () -> backend.infer(request("hello", 10)));
        } finally { backend.close(); }
    }

    private static void queuedDeadlineDoesNotRetireActiveWork() throws Exception {
        Fixture bridge = new Fixture();
        bridge.block = true;
        try (ChromeAiBackend backend = new ChromeAiBackend(deadline -> bridge, false)) {
            CompletableFuture<ModelResponse> active = async(() -> backend.infer(request("hello", 10)));
            check(bridge.started.await(2, TimeUnit.SECONDS), "first inference not started");
            expect(TimeoutException.class, () -> backend.infer(
                    new ModelRequest("queued", 10, Duration.ofMillis(30))));
            check(!bridge.closed, "queued request timeout retired a different request");
            bridge.release.countDown();
            check("fixture output".equals(active.get(2, TimeUnit.SECONDS).text()),
                    "active work corrupted by queued timeout");
            check(bridge.inferences == 1, "expired queued request ran");
        }
    }

    private static void closeCancelsActiveWork() throws Exception {
        Fixture bridge = new Fixture();
        bridge.block = true;
        ChromeAiBackend backend = new ChromeAiBackend(deadline -> bridge, false);
        try {
            CompletableFuture<ModelResponse> active = async(() -> backend.infer(request("hello", 10)));
            check(bridge.started.await(2, TimeUnit.SECONDS), "inference never started");
            backend.close();
            try {
                active.get(2, TimeUnit.SECONDS);
                throw new AssertionError("closed inference returned success");
            } catch (java.util.concurrent.ExecutionException failure) {
                check(failure.getCause() instanceof IOException, "wrong close failure");
            }
            check(bridge.closed && bridge.cancelled.get() != null, "close did not retire and cancel work");
        } finally { backend.close(); }
    }

    private static void cancellationFailureDoesNotMaskDeadline() throws Exception {
        Fixture bridge = new Fixture();
        bridge.stall = true;
        bridge.cancelFailure = true;
        try (ChromeAiBackend backend = new ChromeAiBackend(deadline -> bridge, false)) {
            expect(TimeoutException.class, () -> backend.infer(
                    new ModelRequest("hello", 10, Duration.ofMillis(40))));
            check(bridge.closed, "cancellation failure skipped process retirement");
        }
    }

    private static void closeInterruptsStartup() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ChromeAiBackend backend = new ChromeAiBackend(deadline -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stopped) { interrupted.countDown(); throw stopped; }
            throw new AssertionError("unreachable launch");
        }, false);
        try {
            CompletableFuture<Map<String, Object>> opening = async(() -> backend.status(Duration.ofMinutes(10)));
            check(started.await(2, TimeUnit.SECONDS), "startup never began");
            backend.close();
            check(interrupted.await(2, TimeUnit.SECONDS), "close left startup waiting for the full request deadline");
            try {
                opening.get(2, TimeUnit.SECONDS);
                throw new AssertionError("closed startup returned success");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(expected.getCause() instanceof InterruptedException, "startup close lost interruption");
            }
        } finally { backend.close(); }
    }

    private static void lateStartupCannotEscapeOwnership() throws Exception {
        Fixture bridge = new Fixture();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChromeAiBackend backend = new ChromeAiBackend(deadline -> {
            started.countDown();
            try { release.await(); }
            catch (InterruptedException closed) { /* Deliberately emulate a late native result. */ }
            return bridge;
        }, false);
        try {
            CompletableFuture<Map<String, Object>> opening = async(() -> backend.status(Duration.ofMinutes(10)));
            check(started.await(2, TimeUnit.SECONDS), "startup never began");
            backend.close();
            release.countDown();
            try {
                opening.get(2, TimeUnit.SECONDS);
                throw new AssertionError("late startup escaped a closed backend");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(expected.getCause() instanceof IOException, "late startup returned wrong failure");
            }
            check(bridge.closed, "late startup session leaked");
        } finally { release.countDown(); backend.close(); }
    }

    private static ModelRequest request(String text, int tokens) {
        return new ModelRequest(text, tokens, Duration.ofSeconds(3));
    }

    private static void browserReportedTimeoutRetiresSession() throws Exception {
        for (String operation : java.util.List.of("prepare", "infer")) {
            Fixture bridge = new Fixture();
            Map<String, Object> timeout = Map.of("ok", false, "error", "TIMEOUT");
            if (operation.equals("prepare")) bridge.preparationResult = timeout;
            else bridge.result = timeout;
            try (ChromeAiBackend backend = new ChromeAiBackend(deadline -> bridge, false)) {
                expect(TimeoutException.class, () -> {
                    if (operation.equals("prepare")) backend.prepare(Duration.ofMinutes(20));
                    else backend.infer(request("hello", 10));
                });
                check(bridge.closed, "browser-reported timeout did not retire " + operation);
                expect(IOException.class, () -> backend.status(Duration.ofSeconds(1)));
            }
        }
    }

    private static void failedNativeCleanupRetiresSession() throws Exception {
        for (String operation : java.util.List.of("status", "prepare", "infer")) {
            Fixture bridge = new Fixture();
            Map<String, Object> failure = Map.of("ok", false, "error", "SESSION_CLEANUP_FAILED");
            if (operation.equals("prepare")) bridge.preparationResult = failure;
            else if (operation.equals("infer")) bridge.result = failure;
            else bridge.statusError = "SESSION_CLEANUP_FAILED";
            try (ChromeAiBackend backend = new ChromeAiBackend(deadline -> bridge, false)) {
                expect(IOException.class, () -> {
                    if (operation.equals("prepare")) backend.prepare(Duration.ofSeconds(2));
                    else if (operation.equals("infer")) backend.infer(request("hello", 10));
                    else backend.status(Duration.ofSeconds(2));
                });
                check(bridge.closed, "failed native cleanup did not retire " + operation);
                expect(IOException.class, () -> backend.status(Duration.ofSeconds(1)));
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static <T> CompletableFuture<T> async(Work<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try { result.complete(work.run()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result;
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }

    /** Models external process behavior; all admission, deadlines and receipts use production code. */
    private static final class Fixture implements ChromeAiBackend.Session {
        volatile String availability = "available";
        volatile int preparations;
        volatile int inferences;
        volatile boolean closed;
        volatile boolean stall;
        volatile boolean block;
        volatile boolean cancelFailure;
        volatile String statusError;
        volatile Map<String, Object> preparationResult;
        volatile Map<String, Object> request;
        volatile Map<String, Object> result = Map.of("ok", true, "text", "fixture output",
                "measuredOutputTokens", BigDecimal.valueOf(3));
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<String> cancelled = new AtomicReference<>();

        @Override public Map<String, Object> status(long deadline) {
            Map<String, Object> status = new java.util.LinkedHashMap<>();
            status.put("availability", availability);
            status.put("downloadProgress", null);
            if (statusError != null) status.put("error", statusError);
            return status;
        }

        @Override public Map<String, Object> prepare(boolean allowDownload, long deadline) {
            preparations++;
            if (preparationResult != null) return preparationResult;
            if (!allowDownload && !"available".equals(availability)) {
                return Map.of("ok", false, "error", "PREPARATION_REQUIRED", "availability", availability);
            }
            availability = "available";
            return Map.of("ok", true, "ready", true, "availability", availability);
        }

        @Override public Map<String, Object> infer(Map<String, Object> value, long deadline)
                throws IOException, InterruptedException, TimeoutException {
            request = value;
            inferences++;
            started.countDown();
            if (stall) {
                long nanos = ChromeDevTools.remaining(deadline);
                TimeUnit.NANOSECONDS.sleep(nanos);
                throw new TimeoutException("fixture native wait");
            }
            if (block && !release.await(ChromeDevTools.remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("fixture wait");
            }
            if (closed) throw new IOException("fixture process closed");
            return result;
        }

        @Override public void cancel(String id, long deadline) {
            if (cancelFailure) throw new IllegalStateException("fixture uncertain send");
            cancelled.set(id);
            release.countDown();
        }

        @Override public String version() { return "fixture-chrome"; }
        @Override public void close() { closed = true; release.countDown(); }
    }
}
