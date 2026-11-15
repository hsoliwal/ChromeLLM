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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/** Offline protocol tests: actual downloader decisions with scripted HTTP responses. */
public final class ModelArtifactHttpContractTestMain {
    private static final URI RELEASE = URI.create(
            "https://github.com/hsoliwal/ChromeLLM/releases/download/pinned-tag/weights.bin");
    private static final URI ASSET = URI.create(
            "https://release-assets.githubusercontent.com/asset/model.bin?signature=fixture");
    private static int checks;

    private ModelArtifactHttpContractTestMain() { }

    public static void main(String[] args) throws Exception {
        for (int status : new int[] {301, 302, 303, 307, 308}) {
            TrackedStream redirect = new TrackedStream("redirect");
            TrackedStream bytes = new TrackedStream("model");
            ScriptedClient client = new ScriptedClient(
                    response(status, Map.of("Location", List.of(ASSET.toString())), redirect),
                    response(200, Map.of("Content-Length", List.of("5")), bytes));
            try (InputStream body = ModelArtifactCache.fetch(RELEASE, client)) {
                check(!bytes.closed, "successful response closed before consumption");
                check(new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .equals("model"), "redirect returned wrong bytes");
                check(bytes.closed && client.shutdowns.get() == 1, "EOF did not close body and client");
            }
            check(redirect.closed && bytes.closed, "response body leaked");
            check(client.requests.stream().map(HttpRequest::uri).toList()
                    .equals(List.of(RELEASE, ASSET)), "wrong redirect route");
            check(client.requests.stream().allMatch(r -> r.method().equals("GET")
                    && r.headers().firstValue("Accept-Encoding").orElse("").equals("identity")),
                    "request contract changed");
        }
        for (String forbidden : List.of(
                "http://release-assets.githubusercontent.com/model.bin",
                "https://user:password@release-assets.githubusercontent.com/model.bin",
                "https://release-assets.githubusercontent.com:0/model.bin",
                "https://release-assets.githubusercontent.com:70000/model.bin",
                "https://release-assets.githubusercontent.com/model.bin#fragment", "[malformed", "")) {
            refusal(RELEASE, 302, Map.of("Location", List.of(forbidden)));
        }
        // The current artifact contract supports bounded HTTPS redirects for any
        // explicit source, including CDN hosts and valid non-default HTTPS ports.
        admittedRedirect(URI.create("https://example.invalid/model.bin"), ASSET);
        admittedRedirect(URI.create("https://github.com/hsoliwal/ChromeLLM/blob/main/weights.bin"),
                URI.create("https://cdn.example.invalid:8443/model.bin"));
        admittedRedirect(RELEASE, URI.create("https://cdn.example.invalid/model.bin"));
        refusal(RELEASE, 302, Map.of());
        refusal(RELEASE, 302, Map.of("Location", List.of(ASSET.toString(), ASSET.toString())));
        refusal(RELEASE, 404, Map.of());
        refusal(RELEASE, 200, Map.of("Content-Encoding", List.of("gzip")));
        refusal(RELEASE, 200, Map.of("Content-Length", List.of("68719476737")));
        refusal(RELEASE, 200, Map.of("Content-Length", List.of("not-a-number")));
        refusal(RELEASE, 200, Map.of("Content-Length", List.of("-1")));
        refusal(RELEASE, 200, Map.of("Content-Length", List.of("5", "5")));
        refusal(RELEASE, 200, Map.of("Content-Encoding", List.of("identity", "identity")));

        List<TrackedStream> cycleBodies = new ArrayList<>();
        List<HttpResponse<InputStream>> cycle = new ArrayList<>();
        for (int i = 0; i < ModelArtifactCache.MAX_REDIRECTS + 2; i++) {
            TrackedStream body = new TrackedStream("redirect");
            cycleBodies.add(body);
            cycle.add(response(302, Map.of("Location", List.of(ASSET.resolve("/hop-" + i).toString())), body));
        }
        ScriptedClient cycleClient = new ScriptedClient(cycle);
        expectIo(() -> ModelArtifactCache.fetch(RELEASE, cycleClient));
        check(cycleClient.requests.size() == ModelArtifactCache.MAX_REDIRECTS + 1,
                "redirect chain is not bounded");
        check(cycleBodies.subList(0, cycleClient.requests.size()).stream().allMatch(b -> b.closed),
                "redirect-limit refusal leaked a response");
        check(!cycleBodies.getLast().closed && cycleClient.shutdowns.get() == 1,
                "redirect limit consumed an extra response or leaked its client");

        TrackedStream loopFirst = new TrackedStream("redirect");
        TrackedStream loopSecond = new TrackedStream("redirect");
        ScriptedClient loopClient = new ScriptedClient(
                response(302, Map.of("Location", List.of(ASSET.toString())), loopFirst),
                response(302, Map.of("Location", List.of(RELEASE.toString())), loopSecond));
        expectIo(() -> ModelArtifactCache.fetch(RELEASE, loopClient));
        check(loopClient.requests.size() == 2 && loopClient.shutdowns.get() == 1
                        && loopFirst.closed && loopSecond.closed,
                "redirect cycle repeated a request or leaked owned resources");

        TrackedStream direct = new TrackedStream("direct");
        ScriptedClient directClient = new ScriptedClient(response(200, Map.of(), direct));
        try (InputStream body = ModelArtifactCache.fetch(URI.create("https://example.invalid/model"), directClient)) {
            check(body.readAllBytes().length == 6, "ordinary direct HTTPS download changed");
        }
        check(direct.closed, "direct response leaked");
        IOException closeFailure = new IOException("fixture close failure");
        InputStream brokenClose = new ByteArrayInputStream(new byte[0]) {
            @Override public void close() throws IOException { throw closeFailure; }
        };
        try {
            ModelArtifactCache.fetch(RELEASE, new ScriptedClient(response(404, Map.of(), brokenClose)));
            throw new AssertionError("HTTP failure expected");
        } catch (IOException failure) {
            check(failure.getMessage().contains("HTTP 404"), "close failure replaced transport failure");
            check(List.of(failure.getSuppressed()).contains(closeFailure), "close failure evidence lost");
        }
        System.out.println("model artifact HTTP contract checks passed: " + checks);
    }

    private static void admittedRedirect(URI origin, URI destination) throws Exception {
        TrackedStream redirect = new TrackedStream("redirect");
        TrackedStream bytes = new TrackedStream("model");
        ScriptedClient client = new ScriptedClient(
                response(302, Map.of("Location", List.of(destination.toString())), redirect),
                response(200, Map.of(), bytes));
        try (InputStream body = ModelArtifactCache.fetch(origin, client)) {
            check(!bytes.closed, "admitted response closed before consumption");
            check(new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .equals("model"), "admitted redirect changed bytes");
        }
        check(redirect.closed && bytes.closed && client.shutdowns.get() == 1,
                "admitted redirect leaked body or client");
        check(client.requests.stream().map(HttpRequest::uri).toList().equals(List.of(origin, destination)),
                "admitted HTTPS redirect route changed");
    }

    private static void refusal(URI origin, int status, Map<String, List<String>> headers) throws Exception {
        TrackedStream body = new TrackedStream("rejected");
        ScriptedClient client = new ScriptedClient(response(status, headers, body));
        expectIo(() -> ModelArtifactCache.fetch(origin, client));
        check(body.closed && client.shutdowns.get() == 1, "rejected response or client leaked");
        check(client.requests.size() == 1, "forbidden redirect made another request");
    }

    private static void expectIo(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("expected IOException"); }
        catch (IOException expected) { checks++; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }

    private static final class TrackedStream extends ByteArrayInputStream {
        boolean closed;
        TrackedStream(String text) { super(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    private static HttpResponse<InputStream> response(int status, Map<String, List<String>> fields,
            InputStream body) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return HttpRequest.newBuilder(RELEASE).GET().build(); }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(fields, (k, v) -> true); }
            public InputStream body() { return body; }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return RELEASE; }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    private static final class ScriptedClient extends HttpClient {
        private final ArrayDeque<HttpResponse<InputStream>> responses;
        final List<HttpRequest> requests = new ArrayList<>();
        final AtomicInteger shutdowns = new AtomicInteger();
        ScriptedClient(HttpResponse<InputStream> response) { this(List.of(response)); }
        ScriptedClient(HttpResponse<InputStream> first, HttpResponse<InputStream> second) {
            this(List.of(first, second));
        }
        ScriptedClient(List<HttpResponse<InputStream>> responses) { this.responses = new ArrayDeque<>(responses); }
        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            requests.add(request);
            if (responses.isEmpty()) throw new AssertionError("unexpected HTTP request");
            return (HttpResponse<T>) responses.removeFirst();
        }
        @Override public void shutdownNow() { shutdowns.incrementAndGet(); }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(20)); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch (Exception e) { throw new AssertionError(e); } }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { throw new UnsupportedOperationException(); }
    }
}
