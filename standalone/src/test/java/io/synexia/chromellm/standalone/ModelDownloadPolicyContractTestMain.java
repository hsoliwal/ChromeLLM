// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/** Executes the real redirect/deadline owner with controlled HTTP responses; no internet or model required. */
public final class ModelDownloadPolicyContractTestMain {
    private static int checks;
    private ModelDownloadPolicyContractTestMain() { }

    public static void main(String[] args) throws Exception {
        URI origin = URI.create("https://example.invalid/model.gguf");
        Path cache = Files.createTempDirectory("chromellm-download-policy-");
        byte[] bytes = "fixture-model-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        try {
            rejectConfig(() -> new ModelArtifactCache(origin.toString(), hash, cache, true, Duration.ZERO, 10));
            rejectConfig(() -> new ModelArtifactCache(origin.toString(), hash, cache, true, Duration.ofHours(2), 10));
            rejectConfig(() -> new ModelArtifactCache(origin.toString(), hash, cache, true, Duration.ofSeconds(1), 0));
            FakeClient denied = new FakeClient(reply(302, Map.of("Location", List.of("/next")), empty()));
            rejectIo("HTTP 302", () -> ModelArtifactCache.fetch(origin, false, Duration.ofSeconds(1), 100, denied));
            check(denied.requests.size() == 1 && denied.stopped, "default redirect refusal closes its HTTP client");
            FakeClient permitted = new FakeClient(
                    reply(302, Map.of("Location", List.of("https://cdn.example.invalid/pinned?signature=opaque")), empty()),
                    reply(200, Map.of("Content-Length", List.of(Integer.toString(bytes.length))), new ByteArrayInputStream(bytes)));
            ModelArtifactCache model = new ModelArtifactCache(origin.toString(), hash, cache,
                    uri -> ModelArtifactCache.fetch(uri, true, Duration.ofSeconds(2), 100, permitted), 100);
            Path first = model.resolve();
            check(Files.readAllBytes(first).length == bytes.length && permitted.requests.size() == 2, "explicit CDN redirect downloads once");
            check(permitted.requests.get(1).uri().getHost().equals("cdn.example.invalid"), "redirect target preserved");
            check(permitted.requests.get(1).headers().firstValue("Authorization").isEmpty()
                    && permitted.requests.get(1).headers().firstValue("Cookie").isEmpty(), "no credentials forwarded to redirect target");
            check(permitted.requests.get(1).timeout().orElseThrow().compareTo(permitted.requests.get(0).timeout().orElseThrow()) <= 0,
                    "redirect shares one decreasing deadline");
            check(permitted.stopped, "completed download closes owned client");
            check(model.resolve().equals(first) && permitted.requests.size() == 2, "warm cache makes no HTTP request");
            Files.delete(first);
            for (String unsafe : List.of("http://example.invalid/plain", "https://u:p@example.invalid/m",
                    "https://example.invalid/m#fragment", "https://example.invalid:0/m")) {
                FakeClient client = new FakeClient(reply(307, Map.of("Location", List.of(unsafe)), empty()));
                rejectIo("remain HTTPS", () -> ModelArtifactCache.fetch(origin, true, Duration.ofSeconds(1), 100, client));
                check(client.requests.size() == 1 && client.stopped, "unsafe redirect never reaches a second request");
            }
            FakeClient loop = new FakeClient(reply(308, Map.of("Location", List.of(origin.toString())), empty()));
            rejectIo("loop", () -> ModelArtifactCache.fetch(origin, true, Duration.ofSeconds(1), 100, loop));
            List<Reply> hops = new ArrayList<>();
            for (int i = 0; i < 6; i++) hops.add(reply(302, Map.of("Location", List.of("/hop" + i)), empty()));
            FakeClient tooMany = new FakeClient(hops.toArray(Reply[]::new));
            rejectIo("five HTTPS", () -> ModelArtifactCache.fetch(origin, true, Duration.ofSeconds(1), 100, tooMany));
            check(tooMany.requests.size() == 6, "redirect bound is five followed hops");
            FakeClient encoding = new FakeClient(reply(200, Map.of("Content-Encoding", List.of("gzip")), empty()));
            rejectIo("encoding", () -> ModelArtifactCache.fetch(origin, true, Duration.ofSeconds(1), 100, encoding));
            FakeClient oversized = new FakeClient(reply(200, Map.of("Content-Length", List.of("101")), empty()));
            rejectIo("size", () -> ModelArtifactCache.fetch(origin, true, Duration.ofSeconds(1), 100, oversized));
            FakeClient malformedLength = new FakeClient(reply(200,
                    Map.of("Content-Length", List.of("999999999999999999999999")), empty()));
            rejectIo("content length", () -> ModelArtifactCache.fetch(origin, true, Duration.ofSeconds(1), 100, malformedLength));
            BlockingBody stalled = new BlockingBody();
            FakeClient blocked = new FakeClient(reply(200, Map.of(), stalled));
            ModelArtifactCache deadline = new ModelArtifactCache(origin.toString(), hash, cache,
                    uri -> ModelArtifactCache.fetch(uri, true, Duration.ofMillis(1250), 100, blocked),
                    100, Duration.ofMillis(1250));
            long started = System.nanoTime();
            try {
                deadline.resolve();
                throw new AssertionError("stalled body outlived full download deadline");
            } catch (HttpTimeoutException expected) {
                check(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0,
                        "body deadline unblocks read rather than only timing response headers");
            }
            long cleanupDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while ((!stalled.closed.get() || !blocked.stopped || hasEntries(cache))
                    && System.nanoTime() < cleanupDeadline) Thread.sleep(10);
            check(stalled.closed.get() && blocked.stopped, "deadline closes both body and client");
            check(!hasEntries(cache), "timed-out model has no published or partial cache entry");
            // Construction/status/close of a packaged JNI lazy backend must not download or load JNI.
            try (ModelBackend lazy = StandaloneMain.backend(Map.of("--backend", "jni",
                    "--model-url", origin.toString(), "--model-sha256", hash,
                    "--model-cache-dir", cache.toString(), "--model-follow-redirects", "true"), Map.of())) {
                check(lazy.id().equals("llama.cpp-jni"), "packaged JNI omission retains lazy identity");
            }
            try (var entries = Files.list(cache)) {
                check(entries.findAny().isEmpty(), "uninvoked lazy JNI backend performs no artifact writes");
            }
            check(Thread.getAllStackTraces().keySet().stream().noneMatch(worker ->
                            worker.getName().equals("chromellm-model-download-deadline")
                            && worker.getThreadGroup() == Thread.currentThread().getThreadGroup()),
                    "acquisition must reuse its lifecycle owner without a lingering deadline scheduler");
        } finally {
            try (var entries = Files.walk(cache)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
            }
        }
        System.out.println("model redirect/full-body deadline checks passed: " + checks + "; controlled HTTP responses, no internet/model");
    }

    private static boolean hasEntries(Path directory) throws IOException {
        try (var entries = Files.list(directory)) { return entries.findAny().isPresent(); }
    }

    private static InputStream empty() { return new ByteArrayInputStream(new byte[0]); }
    private static Reply reply(int status, Map<String, List<String>> headers, InputStream body) { return new Reply(status, headers, body); }
    private record Reply(int status, Map<String, List<String>> headers, InputStream body) { }

    private static final class BlockingBody extends InputStream {
        final AtomicBoolean closed = new AtomicBoolean();
        @Override public synchronized int read() throws IOException {
            while (!closed.get()) {
                try { wait(); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fixture read interrupted");
                }
            }
            throw new IOException("fixture body closed");
        }
        @Override public synchronized void close() { closed.set(true); notifyAll(); }
    }

    private static final class FakeClient extends HttpClient {
        final Deque<Reply> replies = new ArrayDeque<>();
        final List<HttpRequest> requests = new ArrayList<>();
        volatile boolean stopped;
        FakeClient(Reply... supplied) { replies.addAll(List.of(supplied)); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            requests.add(request);
            Reply reply = replies.removeFirst();
            return new HttpResponse<>() {
                @Override public int statusCode() { return reply.status(); }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return HttpHeaders.of(reply.headers(), (name, value) -> true); }
                @SuppressWarnings("unchecked") @Override public T body() { return (T) reply.body(); }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }
        @Override public void shutdownNow() { stopped = true; }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                       HttpResponse.PushPromiseHandler<T> push) {
            throw new UnsupportedOperationException();
        }
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejectConfig(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("invalid model policy accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void rejectIo(String fragment, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("unsafe model response accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains(fragment), "unexpected failure: " + expected); }
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
}
