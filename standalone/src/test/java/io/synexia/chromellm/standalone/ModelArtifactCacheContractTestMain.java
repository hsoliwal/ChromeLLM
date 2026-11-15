// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;

/** Offline cache and first-inference contracts; no licensed model or network needed. */
public final class ModelArtifactCacheContractTestMain {
    private static int checks;
    private ModelArtifactCacheContractTestMain() { }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("chromellm-model-cache-");
        byte[] fixture = "synthetic non-model fixture".getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture));
        String url = "https://example.invalid/model.gguf";
        AtomicInteger opens = new AtomicInteger();
        ModelArtifactCache.Downloader download = ignored -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(fixture);
        };
        try {
            reject(() -> new ModelArtifactCache("http://example.invalid/model.gguf", hash, dir, download, 100));
            reject(() -> new ModelArtifactCache("https://u:p@example.invalid/m", hash, dir, download, 100));
            reject(() -> new ModelArtifactCache("https://example.invalid/m#fragment", hash, dir, download, 100));
            reject(() -> new ModelArtifactCache("https://example.invalid:65536/m", hash, dir, download, 100));
            reject(() -> new ModelArtifactCache(url, "bad", dir, download, 100));
            reject(() -> new ModelArtifactCache(url, hash, dir, download, 0));
            ModelArtifactCache cache = new ModelArtifactCache(url, hash.toUpperCase(), dir, download, 100);
            check(opens.get() == 0, "eager model download");
            ModelArtifactCache offline = new ModelArtifactCache(url, hash, dir, download, 100, true);
            check(offline.progress().state() == ModelArtifactCache.State.UNPREPARED,
                    "offline construction started acquisition");
            rejectIo(offline::resolve);
            check(opens.get() == 0, "offline miss opened a network download");
            check(offline.progress().state() == ModelArtifactCache.State.FAILED
                            && offline.progress().bytes() == 0,
                    "offline miss did not retain its failed lifecycle state");
            try (var files = Files.list(dir)) {
                check(files.findAny().isEmpty(), "offline miss wrote a staging file");
            }
            Path file = cache.resolve();
            check(file.getFileName().toString().equals(hash + ".gguf"), "content-address mismatch");
            check(Files.readAllBytes(file).length == fixture.length, "published content mismatch");
            check(opens.get() == 1, "one cache miss expected");
            check(cache.resolve().equals(file) && opens.get() == 1, "cache hit downloaded again");
            check(offline.resolve().equals(file) && opens.get() == 1, "offline hit was not reused");
            check(offline.progress().state() == ModelArtifactCache.State.AVAILABLE
                            && offline.progress().bytes() == fixture.length,
                    "offline hit did not expose verified artifact availability");
            check(new ModelArtifactCache(url, hash, dir, 100, true).resolve().equals(file),
                    "bounded offline constructor did not reuse verified cache");
            Files.writeString(file, "corrupt");
            rejectIo(cache::resolve);
            rejectIo(offline::resolve);
            check(offline.progress().state() == ModelArtifactCache.State.FAILED,
                    "offline corrupt cache was reported as available");
            check(opens.get() == 1, "corrupt cache silently repaired");
            Files.delete(file);
            ModelArtifactCache wrong = new ModelArtifactCache(url, "a".repeat(64), dir, download, 100);
            rejectIo(wrong::resolve);
            check(!Files.exists(dir.resolve("a".repeat(64) + ".gguf")), "mismatched bytes published");
            ModelArtifactCache capped = new ModelArtifactCache(url, hash, dir, download, 3);
            rejectIo(capped::resolve);
            check(!Files.exists(file), "oversize bytes published");
            try (var files = Files.list(dir)) {
                check(files.findAny().isEmpty(), "staging file remained after failure");
            }
            AtomicInteger initialized = new AtomicInteger();
            AtomicInteger inferred = new AtomicInteger();
            LazyModelBackend lazy = new LazyModelBackend("llama.cpp-cli", cache, model -> {
                check(Files.isRegularFile(model), "provider opened before acquisition");
                initialized.incrementAndGet();
                return new ModelBackend() {
                    public String id() { return "llama.cpp-cli"; }
                    public ModelResponse infer(ModelRequest request) {
                        inferred.incrementAndGet();
                        return new ModelResponse("synthetic", id(), "0".repeat(64));
                    }
                };
            });
            check(lazy.id().equals("llama.cpp-cli") && initialized.get() == 0, "lazy construction opened provider");
            ModelRequest request = new ModelRequest("test", 8, java.time.Duration.ofSeconds(10));
            lazy.infer(request);
            lazy.infer(request);
            check(initialized.get() == 1 && inferred.get() == 2, "provider reopened after first inference");
            lazy.close();
            rejectIo(() -> lazy.infer(request));
            check(opens.get() == 4, "unexpected fixture fetch count");
            binaryContracts(dir, fixture, hash, url);
            Files.delete(file);
            AtomicInteger partialClosed = new AtomicInteger();
            ModelArtifactCache partial = new ModelArtifactCache(url, hash, dir, ignored -> new InputStream() {
                @Override public int read() throws IOException { throw new IOException("interrupted transport fixture"); }
                @Override public void close() { partialClosed.incrementAndGet(); }
            }, 100);
            rejectIo(partial::resolve);
            check(partialClosed.get() == 1 && !Files.exists(file), "partial download was published or left open");
            try (var files = Files.list(dir)) {
                check(files.findAny().isEmpty(), "partial download staging file survived");
            }
            responseSenderContracts(fixture);
            redirectContracts(fixture);
            bodyDeadlineContract();
        } finally {
            try (var all = Files.walk(dir)) {
                for (Path pathEntry : all.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(pathEntry);
                }
            }
        }
        System.out.println("lazy model artifact contract checks passed: " + checks);
        ModelAcquisitionLifecycleTestMain.main(new String[0]);
    }

    private static void responseSenderContracts(byte[] fixture) throws Exception {
        URI source = URI.create("https://releases.example.invalid/model");
        URI destination = URI.create("https://assets.example.invalid/model?signature=test");
        for (int status : new int[] {301, 302, 303, 307, 308}) {
            TrackedInputStream redirect = new TrackedInputStream(new byte[0]);
            TrackedInputStream body = new TrackedInputStream(fixture);
            List<HttpRequest> requests = new ArrayList<>();
            try (InputStream result = ModelArtifactCache.fetch(source, fixture.length, request -> {
                requests.add(request);
                return request.uri().equals(source)
                        ? response(status, source, Map.of("Location", List.of(destination.toString())), redirect)
                        : response(200, destination, Map.of("Content-Length", List.of("" + fixture.length)), body);
            })) {
                check(java.util.Arrays.equals(result.readAllBytes(), fixture), "redirect returned incorrect bytes");
                check(redirect.closed && !body.closed, "redirect body lifecycle was incorrect");
            }
            check(body.closed && requests.size() == 2 && requests.getLast().uri().equals(destination),
                    "HTTPS redirect not followed exactly once");
            for (HttpRequest request : requests) {
                check(request.method().equals("GET") && request.bodyPublisher().isEmpty()
                                && request.headers().map().size() == 1
                                && request.headers().firstValue("Accept-Encoding").orElse("").equals("identity"),
                        "redirect request propagated unexpected headers or body");
            }
        }

        for (String location : new String[] {"/moved", "//assets.example.invalid/moved"}) {
            AtomicInteger requests = new AtomicInteger();
            try (InputStream result = ModelArtifactCache.fetch(source, fixture.length, request -> {
                if (requests.getAndIncrement() == 0) {
                    return response(302, source, Map.of("Location", List.of(location)), new TrackedInputStream(new byte[0]));
                }
                check(request.uri().equals(source.resolve(location)), "relative redirect resolved incorrectly");
                return response(200, request.uri(), Map.of(), new TrackedInputStream(fixture));
            })) {
                check(java.util.Arrays.equals(result.readAllBytes(), fixture), "relative redirect failed");
            }
        }

        for (String location : new String[] {"http://assets.example.invalid/model",
                "https://user:secret@assets.example.invalid/model", "https://assets.example.invalid/m#fragment",
                "https://assets.example.invalid:65536/m", "https://", "invalid location", ""}) {
            TrackedInputStream body = new TrackedInputStream(new byte[0]);
            AtomicInteger requests = new AtomicInteger();
            rejectIo(() -> ModelArtifactCache.fetch(source, 100, request -> {
                requests.incrementAndGet();
                return response(302, source, Map.of("Location", List.of(location)), body);
            }));
            check(body.closed && requests.get() == 1, "invalid redirect was followed or its body was not closed");
        }
        for (Map<String, List<String>> headers : List.of(Map.<String, List<String>>of(),
                Map.of("Location", List.of("/one", "/two")))) {
            TrackedInputStream body = new TrackedInputStream(new byte[0]);
            rejectIo(() -> ModelArtifactCache.fetch(source, 100,
                    request -> response(302, source, headers, body)));
            check(body.closed, "missing/ambiguous redirect location left its body open");
        }

        List<TrackedInputStream> loopBodies = new ArrayList<>();
        rejectIo(() -> ModelArtifactCache.fetch(source, 100, request -> {
            TrackedInputStream body = new TrackedInputStream(new byte[0]);
            loopBodies.add(body);
            return response(302, request.uri(), Map.of("Location", List.of(
                    request.uri().equals(source) ? destination.toString() : source.toString())), body);
        }));
        check(loopBodies.size() == 2 && loopBodies.stream().allMatch(body -> body.closed),
                "redirect cycle repeated a request or left bodies open");

        List<TrackedInputStream> limitBodies = new ArrayList<>();
        rejectIo(() -> ModelArtifactCache.fetch(source, 100, request -> {
            TrackedInputStream body = new TrackedInputStream(new byte[0]);
            limitBodies.add(body);
            return response(302, request.uri(), Map.of("Location", List.of("/hop-" + limitBodies.size())), body);
        }));
        check(limitBodies.size() == 6 && limitBodies.stream().allMatch(body -> body.closed),
                "redirect hop limit was not enforced");

        for (int status : new int[] {300, 304, 305, 404, 500}) {
            TrackedInputStream body = new TrackedInputStream(new byte[0]);
            rejectIo(() -> ModelArtifactCache.fetch(source, 100,
                    request -> response(status, source, Map.of("Location", List.of("/other")), body)));
            check(body.closed, "HTTP error response body was not closed");
        }
        for (Map<String, List<String>> headers : List.of(
                Map.of("Content-Encoding", List.of("gzip")),
                Map.of("Content-Encoding", List.of("identity", "gzip")),
                Map.of("Content-Length", List.of("101")),
                Map.of("Content-Length", List.of("-1")),
                Map.of("Content-Length", List.of("invalid")),
                Map.of("Content-Length", List.of("20", "20")))) {
            TrackedInputStream body = new TrackedInputStream(fixture);
            rejectIo(() -> ModelArtifactCache.fetch(source, 100,
                    request -> response(200, source, headers, body)));
            check(body.closed, "unsupported encoding or size left response body open");
        }

        for (int status : new int[] {200, 500}) {
            IOException closingFailure = new IOException("synthetic response close failure");
            AtomicInteger closes = new AtomicInteger();
            InputStream body = new ByteArrayInputStream(fixture) {
                @Override public void close() throws IOException {
                    closes.incrementAndGet();
                    throw closingFailure;
                }
            };
            try {
                ModelArtifactCache.fetch(source, 100,
                        request -> response(status, source, Map.of("Content-Encoding", List.of("gzip")), body));
                throw new AssertionError("failed HTTP response was admitted");
            } catch (IOException failure) {
                check(failure != closingFailure && failure.getSuppressed().length == 1
                                && failure.getSuppressed()[0] == closingFailure && closes.get() == 1,
                        "response cleanup replaced the primary HTTP failure");
            }
        }

        AtomicInteger interruptedRequests = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            try {
                ModelArtifactCache.fetch(source, 100, request -> {
                    interruptedRequests.incrementAndGet();
                    return response(200, source, Map.of(), new TrackedInputStream(fixture));
                });
                throw new AssertionError("interrupted model download was accepted");
            } catch (InterruptedException expected) {
                check(interruptedRequests.get() == 0, "interrupted download opened transport");
            }
        } finally {
            Thread.interrupted();
        }
    }

    private static HttpResponse<InputStream> response(int status, URI uri,
            Map<String, List<String>> headers, InputStream body) {
        return new FixtureResponse(status, HttpHeaders.of(headers, (name, value) -> true), body, uri);
    }

    private record FixtureResponse(int statusCode, HttpHeaders headers, InputStream body, URI uri)
            implements HttpResponse<InputStream> {
        @Override public HttpRequest request() { return HttpRequest.newBuilder(uri).GET().build(); }
        @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private static final class TrackedInputStream extends ByteArrayInputStream {
        boolean closed;
        TrackedInputStream(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    private static void binaryContracts(Path dir, byte[] fixture, String hash, String url) throws Exception {
        AtomicInteger opens = new AtomicInteger();
        ModelArtifactCache.Downloader download = ignored -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(fixture);
        };
        reject(() -> new ModelArtifactCache(url, hash, dir, 0));
        reject(() -> new ModelArtifactCache(url, hash, dir, ModelArtifactCache.MAX_BYTES + 1));
        reject(() -> new ModelArtifactCache(url, hash, dir, download, 100, -1, ".bin"));
        ModelArtifactCache binary = new ModelArtifactCache(url, hash, dir, download, fixture.length,
                fixture.length, ".bin");
        rejectIo(() -> binary.resolveWithOfflinePolicy(true));
        check(opens.get() == 0, "offline miss opened the downloader");
        Path file = binary.resolve();
        check(file.getFileName().toString().equals(hash + ".bin"), "native cache must use its own suffix");
        check(binary.resolveWithOfflinePolicy(true).equals(file) && opens.get() == 1, "offline verified binary hit downloaded");
        check(Files.exists(dir.resolve(hash + ".gguf")), "native artifact replaced existing model cache");
        ModelArtifactCache wrongLength = new ModelArtifactCache(url, hash, dir, download, 100,
                fixture.length + 1, ".bin");
        rejectIo(wrongLength::resolve);
        check(opens.get() == 1, "wrong-length cached artifact was silently repaired");
        Files.delete(file);
        rejectIo(wrongLength::resolve);
        check(!Files.exists(file), "size-mismatched download was published");
        Files.write(file, new byte[fixture.length]);
        rejectIo(() -> binary.resolveWithOfflinePolicy(true));
        check(opens.get() == 2, "offline corrupt cache attempted download");
        Files.delete(file);
        try (var files = Files.list(dir)) {
            check(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")),
                    "binary failure left a staged download");
        }
    }

    private static void redirectContracts(byte[] fixture) throws Exception {
        URI start = URI.create("https://release.example.invalid/download");
        TrackedStream first = new TrackedStream(new byte[0]);
        TrackedStream second = new TrackedStream(new byte[0]);
        TrackedStream body = new TrackedStream(fixture);
        FakeTransport success = new FakeTransport(
                response(302, Map.of("Location", List.of("/asset")), first),
                response(307, Map.of("Location", List.of("https://cdn.example.invalid/file?token=fixture")), second),
                response(200, Map.of("Content-Length", List.of(Integer.toString(fixture.length))), body));
        try (InputStream input = ModelArtifactCache.fetch(start, success, Duration.ofSeconds(5))) {
            check(java.util.Arrays.equals(input.readAllBytes(), fixture), "redirected bytes changed");
            check(success.closes.get() == 1 && body.closes.get() == 1, "EOF did not close response and client");
        }
        check(first.closes.get() == 1 && second.closes.get() == 1 && body.closes.get() == 1,
                "redirect/terminal response bodies not closed exactly once");
        check(success.requests.size() == 3 && success.requests.get(1).uri().equals(start.resolve("/asset"))
                && success.requests.get(2).uri().getHost().equals("cdn.example.invalid"), "redirect resolution lost");
        check(success.requests.stream().allMatch(request -> request.timeout().orElseThrow().compareTo(Duration.ofSeconds(5)) <= 0
                && request.headers().firstValue("Accept-Encoding").orElseThrow().equals("identity")),
                "request deadline or identity encoding missing");

        for (String unsafe : List.of("http://cdn.example.invalid/file", "https://u:p@cdn.example.invalid/file",
                "https://cdn.example.invalid/file#fragment", "https://cdn.example.invalid:70000/file")) {
            TrackedStream redirect = new TrackedStream(new byte[0]);
            FakeTransport transport = new FakeTransport(response(302, Map.of("Location", List.of(unsafe)), redirect));
            rejectIo(() -> ModelArtifactCache.fetch(start, transport, Duration.ofSeconds(5)));
            check(transport.requests.size() == 1 && transport.closes.get() == 1 && redirect.closes.get() == 1,
                    "unsafe redirect followed or response/client leaked");
        }
        List<HttpResponse<InputStream>> chain = new ArrayList<>();
        List<TrackedStream> redirects = new ArrayList<>();
        for (int i = 0; i < ModelArtifactCache.MAX_REDIRECTS + 1; i++) {
            TrackedStream redirect = new TrackedStream(new byte[0]);
            redirects.add(redirect);
            chain.add(response(308, Map.of("Location", List.of("/next-" + i)), redirect));
        }
        FakeTransport tooMany = new FakeTransport(chain);
        rejectIo(() -> ModelArtifactCache.fetch(start, tooMany, Duration.ofSeconds(5)));
        check(tooMany.requests.size() == 6 && tooMany.closes.get() == 1
                && redirects.stream().allMatch(stream -> stream.closes.get() == 1), "redirect bound or cleanup failed");
        chain.set(5, response(200, Map.of(), new TrackedStream(fixture)));
        FakeTransport maximum = new FakeTransport(chain);
        try (InputStream input = ModelArtifactCache.fetch(start, maximum, Duration.ofSeconds(5))) {
            check(java.util.Arrays.equals(input.readAllBytes(), fixture), "five permitted redirects were rejected");
        }
        check(maximum.requests.size() == 6 && maximum.closes.get() == 1, "allowed redirect limit changed");

        for (HttpResponse<InputStream> failure : List.of(
                response(302, Map.of(), new TrackedStream(fixture)),
                response(302, Map.of("Location", List.of("/one", "/two")), new TrackedStream(fixture)),
                response(500, Map.of(), new TrackedStream(fixture)),
                response(200, Map.of("Content-Encoding", List.of("gzip")), new TrackedStream(fixture)),
                response(200, Map.of("Content-Length", List.of("bad")), new TrackedStream(fixture)),
                response(200, Map.of("Content-Length", List.of(Long.toString(ModelArtifactCache.MAX_BYTES + 1))),
                        new TrackedStream(fixture)))) {
            FakeTransport transport = new FakeTransport(failure);
            rejectIo(() -> ModelArtifactCache.fetch(start, transport, Duration.ofSeconds(5)));
            check(transport.closes.get() == 1 && ((TrackedStream) failure.body()).closes.get() == 1,
                    "failed HTTP response/client leaked");
        }
        TrackedStream unused = new TrackedStream(fixture);
        FakeTransport early = new FakeTransport(response(200, Map.of(), unused));
        ModelArtifactCache.fetch(start, early, Duration.ofSeconds(5)).close();
        check(early.closes.get() == 1 && unused.closes.get() == 1, "early close leaked body/client");
        FakeTransport sendFailure = new FakeTransport();
        rejectIo(() -> ModelArtifactCache.fetch(start, sendFailure, Duration.ofSeconds(5)));
        check(sendFailure.closes.get() == 1, "send failure leaked client");
    }

    private static void bodyDeadlineContract() throws Exception {
        class StalledBody extends InputStream {
            boolean closed;
            @Override public synchronized int read() throws IOException {
                while (!closed) {
                    try { wait(); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", interrupted);
                    }
                }
                throw new IOException("body closed while stalled");
            }
            @Override public synchronized void close() { closed = true; notifyAll(); }
        }
        StalledBody body = new StalledBody();
        FakeTransport transport = new FakeTransport(response(200, Map.of(), body));
        InputStream input = ModelArtifactCache.fetch(URI.create("https://example.invalid/stalled"),
                transport, Duration.ofMillis(100));
        CompletableFuture<Integer> read = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try { read.complete(input.read()); }
            catch (Throwable failure) { read.completeExceptionally(failure); }
        });
        try {
            read.get(3, TimeUnit.SECONDS);
            throw new AssertionError("stalled body was accepted");
        } catch (ExecutionException failure) {
            check(failure.getCause() instanceof HttpTimeoutException, "stalled body did not report bounded timeout");
        } finally { input.close(); }
        check(transport.closes.get() == 1 && body.closed, "body timeout did not release client and stream");
    }

    private static final class TrackedStream extends ByteArrayInputStream {
        final AtomicInteger closes = new AtomicInteger();
        TrackedStream(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closes.incrementAndGet(); super.close(); }
    }

    private static final class FakeTransport implements ModelArtifactCache.Transport {
        final ArrayDeque<HttpResponse<InputStream>> responses = new ArrayDeque<>();
        final List<HttpRequest> requests = new ArrayList<>();
        final AtomicInteger closes = new AtomicInteger();
        FakeTransport(List<HttpResponse<InputStream>> responses) { this.responses.addAll(responses); }
        @SafeVarargs FakeTransport(HttpResponse<InputStream>... responses) {
            for (HttpResponse<InputStream> response : responses) this.responses.add(response);
        }
        @Override public HttpResponse<InputStream> send(HttpRequest request) throws IOException {
            requests.add(request);
            if (responses.isEmpty()) throw new IOException("synthetic transport failure");
            return responses.removeFirst();
        }
        @Override public void close() { closes.incrementAndGet(); }
    }

    private static HttpResponse<InputStream> response(int status, Map<String, List<String>> headers, InputStream body) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return HttpRequest.newBuilder(URI.create("https://example.invalid")).build(); }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(headers, (name, value) -> true); }
            public InputStream body() { return body; }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return request().uri(); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void reject(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("invalid artifact configuration accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void rejectIo(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("unsafe cache state accepted"); }
        catch (IOException expected) { checks++; }
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
