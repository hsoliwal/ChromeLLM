// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Browser-independent acquisition lifecycle contracts using bounded, offline fixtures. */
public final class ModelAcquisitionLifecycleTestMain {
    private static final String URL = "https://example.invalid/fixture.gguf";
    private static final byte[] BYTES = "synthetic lifecycle fixture, not model weights"
            .getBytes(StandardCharsets.UTF_8);
    private static final ModelRequest REQUEST = new ModelRequest("fixture", 8, Duration.ofSeconds(30));
    private static int checks;

    private ModelAcquisitionLifecycleTestMain() { }

    public static void main(String[] args) throws Exception {
        Map<String, Checked> cases = new LinkedHashMap<>();
        cases.put("lazy-warm-reuse", ModelAcquisitionLifecycleTestMain::lazyWarmReuse);
        cases.put("close-download", ModelAcquisitionLifecycleTestMain::closeBlockedDownload);
        cases.put("close-opener", ModelAcquisitionLifecycleTestMain::closeBlockedOpener);
        cases.put("opener-retry", ModelAcquisitionLifecycleTestMain::retryFailedOpener);
        cases.put("foreign-target", ModelAcquisitionLifecycleTestMain::foreignTargetSurvives);
        cases.put("progress", ModelAcquisitionLifecycleTestMain::progressWithoutBrowser);
        cases.put("body-deadline", ModelAcquisitionLifecycleTestMain::bodyDeadline);
        cases.put("same-artifact", () -> coordinatedAcquisition(false));
        cases.put("cancel-waiter", () -> coordinatedAcquisition(true));
        cases.put("deferred-native-close", ModelAcquisitionLifecycleTestMain::deferredNativeClose);
        cases.put("close-unprepared", ModelAcquisitionLifecycleTestMain::closeUnprepared);
        cases.put("late-transport", ModelAcquisitionLifecycleTestMain::lateTransportAfterTimeout);
        cases.put("primary-inference-failure", ModelAcquisitionLifecycleTestMain::preserveInferenceFailure);
        cases.put("mcp-lifecycle-status", ModelAcquisitionLifecycleTestMain::mcpLifecycleStatus);
        if (args.length > 1 || (args.length == 1 && !cases.containsKey(args[0]))) {
            throw new IllegalArgumentException("expected one known lifecycle case or no arguments");
        }
        for (var entry : cases.entrySet()) {
            if (args.length == 0 || entry.getKey().equals(args[0])) {
                entry.getValue().run();
                System.out.println("lifecycle case passed: " + entry.getKey());
            }
        }
        System.out.println("native model lifecycle checks passed: " + checks);
    }

    private static void lazyWarmReuse() throws Exception {
        Path directory = Files.createTempDirectory("chromellm-lifecycle-lazy-");
        AtomicInteger downloads = new AtomicInteger();
        AtomicInteger initialized = new AtomicInteger();
        TrackingBackend selected = new TrackingBackend();
        try {
            ModelArtifactCache cache = cache(directory, ignored -> {
                downloads.incrementAndGet();
                return new ByteArrayInputStream(BYTES);
            });
            LazyModelBackend lazy = new LazyModelBackend(selected.id(), cache, verified -> {
                check(Arrays.equals(Files.readAllBytes(verified), BYTES), "provider observed unverified artifact");
                initialized.incrementAndGet();
                return selected;
            });
            try {
                check(lazy.id().equals(selected.id()), "provider identity changed before acquisition");
                check(downloads.get() == 0 && initialized.get() == 0, "construction performed acquisition");
                try (var files = Files.list(directory)) {
                    check(files.findAny().isEmpty(), "construction wrote cache files");
                }
                check(lazy.infer(REQUEST).text().equals("fixture"), "first inference failed");
                check(lazy.infer(REQUEST).text().equals("fixture"), "warm inference failed");
                check(downloads.get() == 1 && initialized.get() == 1,
                        "warm request reacquired artifact or reopened provider");
                check(selected.inferences.get() == 2, "warm requests were not both executed");
                lazy.close();
                lazy.close();
                check(selected.closes.get() == 1, "provider close is not idempotent");
                expectIo(() -> lazy.infer(REQUEST));
                check(Files.isRegularFile(target(directory)), "close deleted verified cache data");
                assertNoStage(directory);
            } finally {
                lazy.close();
            }
        } finally {
            remove(directory);
        }
    }

    private static void closeBlockedDownload() throws Exception {
        Path directory = Files.createTempDirectory("chromellm-lifecycle-close-download-");
        CloseBlockedStream source = new CloseBlockedStream();
        AtomicInteger initialized = new AtomicInteger();
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        LazyModelBackend lazy = new LazyModelBackend("llama.cpp-cli", cache(directory, ignored -> source),
                verified -> {
                    initialized.incrementAndGet();
                    return new TrackingBackend();
                });
        Future<ModelResponse> inference = null;
        try {
            inference = workers.submit(() -> lazy.infer(REQUEST));
            await(source.readEntered, "body read was never entered");
            Future<?> closed = workers.submit(lazy::close);
            closed.get(5, TimeUnit.SECONDS);
            await(source.closed, "close did not release the acquisition stream");
            expectAcquisitionFailure(inference);
            await(source.readExited, "cancelled body read did not unwind");
            check(initialized.get() == 0, "close permitted late provider initialization");
            check(!Files.exists(target(directory)), "cancelled download published a target");
            awaitNoStage(directory);
            check(lazy.progress().state() == ModelArtifactCache.State.CLOSED, "closed owner lost closed state");
            expectIo(() -> lazy.infer(REQUEST));
        } finally {
            source.close();
            source.release.countDown();
            if (inference != null) inference.cancel(true);
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            lazy.close();
            remove(directory);
        }
    }

    private static void closeBlockedOpener() throws Exception {
        Path directory = Files.createTempDirectory("chromellm-lifecycle-close-open-");
        CountDownLatch openerEntered = new CountDownLatch(1);
        CountDownLatch openerRelease = new CountDownLatch(1);
        TrackingBackend candidate = new TrackingBackend();
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        LazyModelBackend lazy = new LazyModelBackend(candidate.id(),
                cache(directory, ignored -> new ByteArrayInputStream(BYTES)), verified -> {
                    openerEntered.countDown();
                    awaitUninterruptibly(openerRelease);
                    return candidate;
                });
        Future<ModelResponse> inference = null;
        try {
            inference = workers.submit(() -> lazy.infer(REQUEST));
            await(openerEntered, "opener was never entered");
            Future<?> closed = workers.submit(lazy::close);
            closed.get(5, TimeUnit.SECONDS);
            check(candidate.closes.get() == 0, "uncreated provider was closed");
            openerRelease.countDown();
            expectAcquisitionFailure(inference);
            check(candidate.closes.get() == 1, "late provider was leaked or closed repeatedly");
            check(candidate.inferences.get() == 0, "inference began after lazy owner closed");
            lazy.close();
            check(candidate.closes.get() == 1, "late provider was closed a second time");
            check(Files.isRegularFile(target(directory)), "cancellation deleted verified model bytes");
        } finally {
            openerRelease.countDown();
            if (inference != null) inference.cancel(true);
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            lazy.close();
            remove(directory);
        }
    }

    private static void retryFailedOpener() throws Exception {
        Path directory = Files.createTempDirectory("chromellm-lifecycle-retry-");
        AtomicInteger downloads = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        TrackingBackend selected = new TrackingBackend();
        LazyModelBackend lazy = new LazyModelBackend(selected.id(), cache(directory, ignored -> {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(BYTES);
        }), verified -> {
            if (opens.incrementAndGet() == 1) throw new IOException("synthetic opener failure");
            return selected;
        });
        try {
            expectIo(() -> lazy.infer(REQUEST));
            check(Files.isRegularFile(target(directory)), "opener failure removed the verified artifact");
            check(lazy.infer(REQUEST).text().equals("fixture"), "explicit retry did not recover");
            check(downloads.get() == 1 && opens.get() == 2, "explicit retry duplicated a verified download");
            check(selected.inferences.get() == 1, "failed opener ran inference");
        } finally {
            lazy.close();
            check(selected.closes.get() == 1, "recovered provider leaked");
            remove(directory);
        }
    }

    private static void foreignTargetSurvives() throws Exception {
        Path directory = Files.createTempDirectory("chromellm-lifecycle-foreign-");
        byte[] foreign = "concurrently published foreign bytes".getBytes(StandardCharsets.UTF_8);
        AtomicInteger downloads = new AtomicInteger();
        try {
            Path target = target(directory);
            ModelArtifactCache cache = cache(directory, ignored -> {
                downloads.incrementAndGet();
                Files.write(target, foreign);
                return new ByteArrayInputStream(BYTES);
            });
            expectIo(cache::resolve);
            check(Arrays.equals(Files.readAllBytes(target), foreign), "publication overwrote a foreign target");
            assertNoStage(directory);
            expectIo(cache::resolve);
            check(downloads.get() == 1, "corrupt cache was silently redownloaded");
            check(Arrays.equals(Files.readAllBytes(target), foreign), "cache rejection changed foreign data");
        } finally {
            remove(directory);
        }
    }

    private static void progressWithoutBrowser() throws Exception {
        Path directory = Files.createTempDirectory("chromellm-lifecycle-progress-");
        ChunkGateStream source = new ChunkGateStream();
        AtomicInteger downloads = new AtomicInteger();
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        ModelArtifactCache cache = cache(directory, ignored -> {
            downloads.incrementAndGet();
            return source;
        });
        TrackingBackend selected = new TrackingBackend();
        LazyModelBackend lazy = new LazyModelBackend(selected.id(), cache, verified -> selected);
        Future<ModelResponse> inference = null;
        try {
            for (int i = 0; i < 5; i++) {
                check(lazy.progress().state() == ModelArtifactCache.State.UNPREPARED,
                        "status changed an unprepared lifecycle");
                check(lazy.progress().bytes() == 0, "unprepared lifecycle reported transferred bytes");
            }
            check(downloads.get() == 0, "status triggered a model download");
            try (var files = Files.list(directory)) {
                check(files.findAny().isEmpty(), "status wrote model cache files");
            }
            inference = workers.submit(() -> lazy.infer(REQUEST));
            await(source.tailEntered, "fixture prefix was not read");
            var progress = lazy.progress();
            check(progress.state() == ModelArtifactCache.State.DOWNLOADING,
                    "native status did not expose acquisition state");
            check(progress.bytes() == ChunkGateStream.PREFIX, "native progress did not account for prefix bytes");
            source.release.countDown();
            inference.get(5, TimeUnit.SECONDS);
            check(cache.progress().state() == ModelArtifactCache.State.AVAILABLE,
                    "verified artifact did not become available");
            check(lazy.progress().state() == ModelArtifactCache.State.READY,
                    "initialized provider did not become ready");
            check(lazy.progress().bytes() == BYTES.length, "ready model byte count is wrong");
            check(downloads.get() == 1 && selected.inferences.get() == 1,
                    "one request acquired or inferred more than once");
        } finally {
            source.release.countDown();
            source.close();
            if (inference != null) inference.cancel(true);
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            lazy.close();
            remove(directory);
        }
    }

    private static void bodyDeadline() throws Exception {
        Path directory = Files.createTempDirectory("chromellm-lifecycle-deadline-");
        CloseBlockedStream source = new CloseBlockedStream();
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        ModelArtifactCache cache = new ModelArtifactCache(URL, hash(), directory,
                ignored -> source, 4096, Duration.ofSeconds(2));
        Future<Path> acquisition = null;
        try {
            acquisition = workers.submit(cache::resolve);
            await(source.readEntered, "deadline fixture did not enter body read");
            try {
                acquisition.get(5, TimeUnit.SECONDS);
                throw new AssertionError("body deadline admitted a blocked download");
            } catch (ExecutionException failure) {
                check(failure.getCause() instanceof HttpTimeoutException,
                        "body deadline returned wrong failure: " + failure.getCause());
            }
            await(source.closed, "body deadline did not close the stream");
            await(source.readExited, "body deadline did not release the read");
            awaitNoStage(directory);
            check(!Files.exists(target(directory)), "timed-out artifact was published");
            check(cache.progress().state() == ModelArtifactCache.State.FAILED,
                    "timeout state was overwritten during asynchronous cleanup");
        } finally {
            source.close();
            if (acquisition != null) acquisition.cancel(true);
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            remove(directory);
        }
    }

    private static void coordinatedAcquisition(boolean cancelWaiter) throws Exception {
        Path directory = Files.createTempDirectory("chromellm-lifecycle-coordinate-");
        ChunkGateStream source = new ChunkGateStream();
        AtomicInteger downloads = new AtomicInteger();
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        ModelArtifactCache first = cache(directory, ignored -> {
            downloads.incrementAndGet();
            return source;
        });
        ModelArtifactCache second = new ModelArtifactCache("https://example.invalid/mirror.gguf", hash(),
                directory, ignored -> {
                    downloads.incrementAndGet();
                    return new ByteArrayInputStream(BYTES);
                }, 4096);
        Future<Path> owner = null;
        Future<Path> waiter = null;
        try {
            owner = workers.submit(first::resolve);
            await(source.tailEntered, "owner did not acquire the fixture body");
            waiter = workers.submit(second::resolve);
            awaitState(() -> second.progress().state(), ModelArtifactCache.State.WAITING,
                    "same-artifact caller did not wait for its owner");
            check(downloads.get() == 1, "same artifact was downloaded concurrently");
            if (cancelWaiter) {
                check(waiter.cancel(true), "waiting caller was not cancellable");
                awaitState(() -> second.progress().state(), ModelArtifactCache.State.CANCELLED,
                        "interrupted waiter did not report cancellation");
                check(first.progress().state() == ModelArtifactCache.State.DOWNLOADING,
                        "waiter cancellation changed the owning request");
                check(!source.isClosed, "waiter cancellation closed another caller's stream");
            }
            source.release.countDown();
            Path verified = owner.get(5, TimeUnit.SECONDS);
            if (!cancelWaiter) check(waiter.get(5, TimeUnit.SECONDS).equals(verified),
                    "coordinated callers resolved different targets");
            check(second.resolve().equals(verified), "explicit follow-up did not reuse the completed artifact");
            check(downloads.get() == 1, "coordinated cache reuse opened the network again");
            check(Arrays.equals(Files.readAllBytes(verified), BYTES), "coordinated publication changed bytes");
            assertNoStage(directory);
        } finally {
            source.release.countDown();
            source.close();
            if (owner != null) owner.cancel(true);
            if (waiter != null) waiter.cancel(true);
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            remove(directory);
        }
    }

    private static void deferredNativeClose() throws Exception {
        Path directory = Files.createTempDirectory("chromellm-lifecycle-native-close-");
        CountDownLatch inferenceEntered = new CountDownLatch(1);
        CountDownLatch inferenceRelease = new CountDownLatch(1);
        AtomicBoolean active = new AtomicBoolean();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger concurrentCloses = new AtomicInteger();
        ModelBackend selected = new ModelBackend() {
            @Override public String id() { return "llama.cpp-jni"; }
            @Override public ModelResponse infer(ModelRequest request) {
                active.set(true);
                inferenceEntered.countDown();
                try {
                    awaitUninterruptibly(inferenceRelease);
                    return new ModelResponse("fixture", id(), promptSha256(request.prompt()));
                } finally {
                    active.set(false);
                }
            }
            @Override public void close() {
                if (active.get()) concurrentCloses.incrementAndGet();
                closes.incrementAndGet();
            }
        };
        LazyModelBackend lazy = new LazyModelBackend(selected.id(),
                cache(directory, ignored -> new ByteArrayInputStream(BYTES)), verified -> selected);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        Future<ModelResponse> inference = null;
        try {
            inference = workers.submit(() -> lazy.infer(REQUEST));
            await(inferenceEntered, "provider did not enter inference");
            workers.submit(lazy::close).get(5, TimeUnit.SECONDS);
            check(lazy.progress().state() == ModelArtifactCache.State.CLOSED,
                    "closed native owner is still advertised as ready");
            check(closes.get() == 0 && concurrentCloses.get() == 0,
                    "native provider was disposed during inference");
            inferenceRelease.countDown();
            try {
                inference.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                check(failure.getCause() instanceof IOException || failure.getCause() instanceof InterruptedException,
                        "unexpected in-flight cancellation failure: " + failure.getCause());
            }
            check(closes.get() == 1 && concurrentCloses.get() == 0,
                    "deferred provider cleanup leaked or raced with native inference");
            lazy.close();
            check(closes.get() == 1, "deferred native provider was closed twice");
            expectIo(() -> lazy.infer(REQUEST));
        } finally {
            inferenceRelease.countDown();
            if (inference != null) inference.cancel(true);
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            lazy.close();
            remove(directory);
        }
    }

    private static void closeUnprepared() throws Exception {
        Path directory = Files.createTempDirectory("chromellm-lifecycle-unprepared-");
        AtomicInteger effects = new AtomicInteger();
        LazyModelBackend lazy = new LazyModelBackend("llama.cpp-cli", cache(directory, ignored -> {
            effects.incrementAndGet();
            return new ByteArrayInputStream(BYTES);
        }), verified -> {
            effects.incrementAndGet();
            return new TrackingBackend();
        });
        try {
            lazy.close();
            check(lazy.progress().state() == ModelArtifactCache.State.CLOSED, "unprepared close was not observable");
            expectIo(() -> lazy.infer(REQUEST));
            check(effects.get() == 0, "closed unprepared owner acquired or initialized a model");
            try (var files = Files.list(directory)) {
                check(files.findAny().isEmpty(), "unprepared close wrote cache files");
            }
        } finally {
            lazy.close();
            remove(directory);
        }
    }

    private static void lateTransportAfterTimeout() throws Exception {
        Path directory = Files.createTempDirectory("chromellm-lifecycle-late-transport-");
        CountDownLatch openEntered = new CountDownLatch(1);
        CountDownLatch openRelease = new CountDownLatch(1);
        CountDownLatch lateSourceClosed = new CountDownLatch(1);
        AtomicInteger downloads = new AtomicInteger();
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        ModelArtifactCache cache = new ModelArtifactCache(URL, hash(), directory, ignored -> {
            if (downloads.incrementAndGet() == 1) {
                openEntered.countDown();
                awaitUninterruptibly(openRelease);
                return new ByteArrayInputStream(BYTES) {
                    @Override public void close() { lateSourceClosed.countDown(); }
                };
            }
            return new ByteArrayInputStream(BYTES);
        }, 4096, Duration.ofSeconds(2));
        Future<Path> first = null;
        Future<Path> retry = null;
        try {
            first = workers.submit(cache::resolve);
            await(openEntered, "late transport fixture never began opening");
            try {
                first.get(5, TimeUnit.SECONDS);
                throw new AssertionError("blocked transport opening exceeded its deadline");
            } catch (ExecutionException failure) {
                check(failure.getCause() instanceof HttpTimeoutException,
                        "transport deadline returned wrong failure: " + failure.getCause());
            }
            check(cache.progress().state() == ModelArtifactCache.State.FAILED,
                    "timed-out opening did not report failure");
            check(!Files.exists(target(directory)), "timed-out opening published model data");
            retry = workers.submit(cache::resolve);
            awaitState(() -> cache.progress().state(), ModelArtifactCache.State.WAITING,
                    "new request did not supersede the timed-out observation");
            openRelease.countDown();
            await(lateSourceClosed, "late-created transport leaked after timeout");
            Path verified = retry.get(5, TimeUnit.SECONDS);
            check(Arrays.equals(Files.readAllBytes(verified), BYTES), "retry did not receive the verified artifact");
            check(downloads.get() == 2, "late aborted opening supplied the retry's artifact");
            check(cache.progress().state() == ModelArtifactCache.State.AVAILABLE,
                    "stale worker replaced the new successful snapshot");
            check(cache.progress().bytes() == BYTES.length, "stale worker replaced the new byte count");
            awaitNoStage(directory);
        } finally {
            openRelease.countDown();
            if (first != null) first.cancel(true);
            if (retry != null) retry.cancel(true);
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            remove(directory);
        }
    }

    private static void preserveInferenceFailure() throws Exception {
        Path directory = Files.createTempDirectory("chromellm-lifecycle-primary-failure-");
        CountDownLatch inferenceEntered = new CountDownLatch(1);
        CountDownLatch inferenceRelease = new CountDownLatch(1);
        IOException primary = new IOException("synthetic inference primary");
        IllegalStateException cleanup = new IllegalStateException("synthetic provider cleanup");
        AtomicInteger closes = new AtomicInteger();
        ModelBackend selected = new ModelBackend() {
            @Override public String id() { return "llama.cpp-jni"; }
            @Override public ModelResponse infer(ModelRequest request) throws IOException {
                inferenceEntered.countDown();
                awaitUninterruptibly(inferenceRelease);
                throw primary;
            }
            @Override public void close() {
                closes.incrementAndGet();
                throw cleanup;
            }
        };
        LazyModelBackend lazy = new LazyModelBackend(selected.id(),
                cache(directory, ignored -> new ByteArrayInputStream(BYTES)), verified -> selected);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        Future<ModelResponse> inference = null;
        try {
            inference = workers.submit(() -> lazy.infer(REQUEST));
            await(inferenceEntered, "failing provider did not enter inference");
            workers.submit(lazy::close).get(5, TimeUnit.SECONDS);
            inferenceRelease.countDown();
            try {
                inference.get(5, TimeUnit.SECONDS);
                throw new AssertionError("failing provider returned success");
            } catch (ExecutionException failure) {
                check(failure.getCause() == primary, "deferred cleanup replaced the primary inference failure");
                check(Arrays.asList(primary.getSuppressed()).contains(cleanup),
                        "cleanup failure was not retained as suppressed evidence");
            }
            check(closes.get() == 1, "failing deferred cleanup was invoked repeatedly");
            lazy.close();
        } finally {
            inferenceRelease.countDown();
            if (inference != null) inference.cancel(true);
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            lazy.close();
            remove(directory);
        }
    }

    private static void mcpLifecycleStatus() throws Exception {
        Path directory = Files.createTempDirectory("chromellm-lifecycle-mcp-");
        String prompt = "sensitive fixture prompt that must not appear in status";
        AtomicInteger downloads = new AtomicInteger();
        AtomicInteger initialized = new AtomicInteger();
        TrackingBackend selected = new TrackingBackend();
        LazyModelBackend lazy = new LazyModelBackend(selected.id(), cache(directory, ignored -> {
            downloads.incrementAndGet();
            return new ByteArrayInputStream(BYTES);
        }), verified -> {
            initialized.incrementAndGet();
            return selected;
        });
        try {
            assertMcpStatus(lazy, ModelArtifactCache.State.UNPREPARED, 0, directory, prompt);
            assertMcpStatus(lazy, ModelArtifactCache.State.UNPREPARED, 0, directory, prompt);
            check(downloads.get() == 0 && initialized.get() == 0,
                    "MCP status caused model download or provider initialization");
            try (var files = Files.list(directory)) {
                check(files.findAny().isEmpty(), "MCP status wrote a cache artifact");
            }
            String inference = mcpTool(lazy, "infer_text", Map.of(
                    "prompt", prompt, "maxTokens", 8, "timeoutSeconds", 30));
            check(!Boolean.TRUE.equals(mcpResult(inference).get("isError")),
                    "MCP inference did not initialize the native model");
            assertMcpStatus(lazy, ModelArtifactCache.State.READY, BYTES.length, directory, prompt);
            check(downloads.get() == 1 && initialized.get() == 1 && selected.inferences.get() == 1,
                    "MCP lifecycle performed duplicate model work");
            lazy.close();
            assertMcpStatus(lazy, ModelArtifactCache.State.CLOSED, BYTES.length, directory, prompt);
            check(downloads.get() == 1 && initialized.get() == 1 && selected.closes.get() == 1,
                    "MCP closed status reopened or disposed the provider again");
        } finally {
            lazy.close();
            remove(directory);
        }
    }

    private static void assertMcpStatus(ModelBackend backend, ModelArtifactCache.State expected,
            long bytes, Path directory, String prompt) throws Exception {
        String wire = mcpTool(backend, "backend_status", Map.of());
        Map<?, ?> result = mcpResult(wire);
        check(Boolean.FALSE.equals(result.get("isError")), "MCP lifecycle status returned an error");
        check(result.get("content") instanceof List<?> content && content.size() == 1
                        && content.get(0) instanceof Map<?, ?> item && "text".equals(item.get("type"))
                        && backend.id().equals(item.get("text")),
                "MCP status changed the established text provider ID");
        if (!(result.get("structuredContent") instanceof Map<?, ?> status)) {
            throw new AssertionError("MCP status omitted native lifecycle fields");
        }
        check(status.keySet().equals(Set.of("backendId", "modelState", "processedBytes")),
                "MCP status exposed unexpected fields");
        check(backend.id().equals(status.get("backendId")), "MCP lifecycle provider ID is wrong");
        check(expected.name().equals(status.get("modelState")), "MCP lifecycle state is wrong");
        check(status.get("processedBytes") instanceof BigDecimal count && count.longValueExact() == bytes,
                "MCP lifecycle byte count is wrong");
        check(!wire.contains(URL) && !wire.contains(directory.toString()) && !wire.contains(prompt),
                "MCP lifecycle status disclosed model location or prompt");
    }

    private static String mcpTool(ModelBackend backend, String name, Map<String, Object> arguments)
            throws IOException {
        String initialize = McpJson.encode(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", McpStdioServer.VERSION, "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "lifecycle-fixture", "version", "1"))), 4096);
        String initialized = McpJson.encode(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), 4096);
        String request = McpJson.encode(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                "params", Map.of("name", name, "arguments", arguments)), 4096);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpStdioServer.serve(backend, new ByteArrayInputStream(
                (initialize + '\n' + initialized + '\n' + request + '\n').getBytes(StandardCharsets.UTF_8)), output);
        String[] replies = output.toString(StandardCharsets.UTF_8).split("\n");
        check(replies.length == 2, "MCP handshake/tool produced an unexpected response count");
        return replies[1];
    }

    private static Map<?, ?> mcpResult(String wire) throws IOException {
        if (!(McpJson.parse(wire) instanceof Map<?, ?> response)
                || !(response.get("result") instanceof Map<?, ?> result)) {
            throw new AssertionError("MCP response lacks a result");
        }
        return result;
    }

    private static ModelArtifactCache cache(Path directory, ModelArtifactCache.Downloader downloader)
            throws Exception {
        return new ModelArtifactCache(URL, hash(), directory, downloader, 4096);
    }

    private static Path target(Path directory) throws Exception {
        return directory.resolve(hash() + ".gguf");
    }

    private static String hash() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES));
    }

    private static String promptSha256(String prompt) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(prompt.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void assertNoStage(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            check(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")),
                    "failed or completed acquisition retained a staging file");
        }
    }

    private static void awaitNoStage(Path directory) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try (var files = Files.list(directory)) {
                if (files.noneMatch(path -> path.getFileName().toString().endsWith(".part"))) {
                    checks++;
                    return;
                }
            }
            if (deadline - System.nanoTime() <= 0) throw new AssertionError("cancelled acquisition retained staging data");
            Thread.yield();
        }
    }

    private static void awaitState(Supplier<ModelArtifactCache.State> state, ModelArtifactCache.State expected,
            String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (state.get() != expected) {
            if (deadline - System.nanoTime() <= 0) throw new AssertionError(message + ": " + state.get());
            Thread.yield();
        }
        checks++;
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        check(latch.await(5, TimeUnit.SECONDS), message);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void expectAcquisitionFailure(Future<?> result) throws Exception {
        try {
            result.get(5, TimeUnit.SECONDS);
            throw new AssertionError("closed acquisition returned a successful result");
        } catch (ExecutionException failure) {
            check(failure.getCause() instanceof IOException || failure.getCause() instanceof InterruptedException,
                    "unexpected acquisition failure: " + failure.getCause());
        }
    }

    private static void expectIo(Checked action) throws Exception {
        try {
            action.run();
            throw new AssertionError("operation unexpectedly succeeded");
        } catch (IOException expected) {
            checks++;
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void remove(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }

    private static final class TrackingBackend implements ModelBackend {
        private final AtomicInteger inferences = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        @Override public String id() { return "llama.cpp-cli"; }
        @Override public ModelResponse infer(ModelRequest request) {
            inferences.incrementAndGet();
            return new ModelResponse("fixture", id(), promptSha256(request.prompt()));
        }
        @Override public void close() { closes.incrementAndGet(); }
    }

    /** Models a body read whose only reliable cancellation mechanism is closing the body. */
    private static final class CloseBlockedStream extends InputStream {
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch readExited = new CountDownLatch(1);
        private volatile boolean isClosed;

        @Override public int read() throws IOException {
            return read(new byte[1], 0, 1);
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            readEntered.countDown();
            try {
                awaitUninterruptibly(release);
                if (isClosed) throw new IOException("synthetic stream closed");
                return -1;
            } finally {
                readExited.countDown();
            }
        }

        @Override public void close() {
            isClosed = true;
            closed.countDown();
            release.countDown();
        }
    }

    /** Releases a known prefix, then holds the remainder to make progress observable. */
    private static final class ChunkGateStream extends InputStream {
        private static final int PREFIX = 7;
        private final CountDownLatch tailEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private int offset;
        private volatile boolean isClosed;

        @Override public int read() throws IOException {
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override public int read(byte[] bytes, int start, int length) throws IOException {
            if (length == 0) return 0;
            if (offset >= PREFIX) {
                tailEntered.countDown();
                awaitUninterruptibly(release);
            }
            if (isClosed) throw new IOException("synthetic stream closed");
            if (offset == BYTES.length) return -1;
            int available = offset < PREFIX ? PREFIX - offset : BYTES.length - offset;
            int count = Math.min(length, available);
            System.arraycopy(BYTES, offset, bytes, start, count);
            offset += count;
            return count;
        }

        @Override public void close() {
            isClosed = true;
            release.countDown();
        }
    }
}
