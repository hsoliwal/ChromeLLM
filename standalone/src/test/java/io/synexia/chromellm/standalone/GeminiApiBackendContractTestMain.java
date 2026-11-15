// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Executable loopback HTTP contracts; no Google account, network API or trained model is used. */
public final class GeminiApiBackendContractTestMain {
    private static final String KEY = "contract-secret-do-not-echo";
    private static final String PROMPT = "private prompt: \"quoted\"\nनमस्ते 🚀 \\ tail\t";
    private static int checks;

    private GeminiApiBackendContractTestMain() { }

    public static void main(String[] args) throws Exception {
        configurationContracts();
        try (Server server = new Server()) {
            requestAndResponseContracts(server);
            requestBounds(server);
            rejectedResponses(server);
            deadlineAndLifecycleContracts(server);
        }
        System.out.println("Gemini API contract checks passed: " + checks);
    }

    private static void requestBounds(Server server) throws Exception {
        server.reply = exchange -> send(exchange, 200, success("bounded"));
        try (GeminiApiBackend backend = server.backend("high")) {
            backend.infer(new ModelRequest("x", 1, Duration.ofSeconds(5)));
            int overhead = server.requests.getLast().body.getBytes(StandardCharsets.UTF_8).length - 1;
            int promptBytes = GeminiApiBackend.MAX_REQUEST_BYTES - overhead;
            String boundary = "🚀".repeat(promptBytes / 4) + "a".repeat(promptBytes % 4);
            backend.infer(new ModelRequest(boundary, 1, Duration.ofSeconds(10)));
            check(server.requests.getLast().body.getBytes(StandardCharsets.UTF_8).length
                    == GeminiApiBackend.MAX_REQUEST_BYTES, "supplementary Unicode at exact request byte limit");
            int before = server.requests.size();
            fails(IOException.class, () -> backend.infer(
                    new ModelRequest(boundary + "a", 1, Duration.ofSeconds(1))));
            fails(IOException.class, () -> backend.infer(
                    new ModelRequest("a".repeat(GeminiApiBackend.MAX_REQUEST_BYTES + 1), 1, Duration.ofSeconds(1))));
            fails(IOException.class, () -> backend.infer(
                    new ModelRequest("\u0001".repeat(GeminiApiBackend.MAX_REQUEST_BYTES / 6 + 1), 1, Duration.ofSeconds(1))));
            check(server.requests.size() == before, "oversized raw and escaped requests rejected before sending");
        }
    }

    private static void configurationContracts() throws Exception {
        for (String key : new String[] {"", " ", "bad\r\nheader", "bad key", "é", "x".repeat(4097)}) {
            fails(IllegalArgumentException.class, () -> new GeminiApiBackend(key, "gemini-4-hydrogen", "high"));
        }
        for (String model : List.of("", "../other", "bad?key=secret", "models/gemini", "bad:model")) {
            fails(IllegalArgumentException.class, () -> new GeminiApiBackend(KEY, model, "high"));
        }
        for (String thinking : List.of("", "minimal", " high ", "xhigh\n")) {
            fails(IllegalArgumentException.class, () -> new GeminiApiBackend(KEY, "gemini-4-hydrogen", thinking));
        }
        for (String endpoint : List.of("http://example.com/v1beta/", "https://example.com/v1beta/",
                "http://localhost:8080/v1beta/", "http://127.0.0.1:8080/v1beta/?key=secret",
                "http://user@127.0.0.1:8080/v1beta/", "http://127.0.0.1:8080/v1beta/#fragment",
                "http://127.0.0.1:8080/other/", "http://127.0.0.1:65536/v1beta/")) {
            fails(IllegalArgumentException.class, () -> new GeminiApiBackend(KEY,
                    "gemini-4-hydrogen", "high", URI.create(endpoint)));
        }
    }

    private static void requestAndResponseContracts(Server server) throws Exception {
        server.reply = exchange -> send(exchange, 200, """
                {"candidates":[{"content":{"role":"model","parts":[
                  {"thought":true,"text":"internal-only"},
                  {"text":"hello\\n\\uD83D\\uDE80","thoughtSignature":"opaque"},
                  {"text":" world","thought":false}]},"finishReason":"STOP",
                  "safetyRatings":[{"blocked":false}]}],
                 "usageMetadata":{"totalTokenCount":123},"extra":[null,true,false,-1.2e+3]}
                """);
        try (GeminiApiBackend backend = server.backend("xHiGh")) {
            ModelResponse result = backend.infer(request(Duration.ofSeconds(5)));
            check(result.text().equals("hello\n🚀 world"), "text parts and Unicode");
            check(result.backendId().equals("gemini-api:gemini-4-hydrogen"), "provider identity");
            check(result.promptSha256().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(PROMPT.getBytes(StandardCharsets.UTF_8)))), "prompt receipt binds exact UTF-8");
            Captured captured = server.requests.getLast();
            check(captured.method.equals("POST"), "HTTP method");
            check(captured.uri.toString().equals("/v1beta/models/gemini-4-hydrogen:generateContent"), "model route");
            check(captured.key.equals(KEY), "key in header");
            check(captured.contentType.equals("application/json; charset=utf-8"), "content type");
            check(!captured.body.contains(KEY) && !captured.uri.toString().contains(KEY), "key absent from body and URI");
            String expected = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"private prompt: "
                    + "\\\"quoted\\\"\\nनमस्ते 🚀 \\\\ tail\\t\"}]}],\"generationConfig\":{\"candidateCount\":1,"
                    + "\"maxOutputTokens\":128,\"responseModalities\":[\"TEXT\"],\"thinkingConfig\":{"
                    + "\"thinkingLevel\":\"XHIGH\",\"includeThoughts\":false}}}";
            check(captured.body.equals(expected), "request wire format and escaping");
        }
        server.reply = exchange -> send(exchange, 200, success("ready"));
        for (String level : List.of("low", "MEDIUM", "high", "XHIGH")) {
            try (GeminiApiBackend backend = server.backend(level)) {
                backend.infer(request(Duration.ofSeconds(5)));
                check(server.requests.getLast().body.contains("\"thinkingLevel\":\""
                        + level.toUpperCase(java.util.Locale.ROOT) + "\""), "thinking level " + level);
            }
        }
    }

    private static void rejectedResponses(Server server) throws Exception {
        try (GeminiApiBackend backend = server.backend("high")) {
            List<String> invalid = List.of(
                    "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}",
                    "{\"candidates\":[]}",
                    success("partial").replace("STOP", "MAX_TOKENS"),
                    success("partial").replace("STOP", "CONTINUATION"),
                    success("unsafe").replace("STOP", "SAFETY"),
                    success("missing").replace(",\"finishReason\":\"STOP\"", ""),
                    success(" "),
                    "{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hidden\"}]},\"finishReason\":\"STOP\"}]}",
                    "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"tool\"}}]},\"finishReason\":\"STOP\"}]}",
                    "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"a\",\"inlineData\":{}}]},\"finishReason\":\"STOP\"}]}",
                    success("a").replace("\"text\":\"a\"", "\"text\":\"a\",\"thought\":\"false\""),
                    success("a").replace("\"finishReason\":\"STOP\"", "\"finishReason\":\"STOP\",\"safetyRatings\":[{\"blocked\":true}]"),
                    success("a").replace("\"text\":\"a\"", "\"text\":42"),
                    success("a").replace("\"text\":\"a\"", "\"text\":null,\"text\":\"a\""),
                    success("a").replace("\"text\":\"a\"", "\"text\":\"\\uD800\""),
                    success("a").replace("\"text\":\"a\"", "\"text\":\"\\uDC00\""),
                    success("a").replace("\"text\":\"a\"", "\"text\":\"\\uZZZZ\""),
                    success("a").replace("\"text\":\"a\"", "\"text\":\"bad\nnewline\""),
                    success("a") + " trailing",
                    withMetadata("01"), withMetadata("1."), withMetadata("1e"), withMetadata("+1"),
                    withMetadata("[1,]"), withMetadata("true,"), withMetadata("NaN"),
                    withMetadata("[".repeat(70) + "0" + "]".repeat(70)),
                    withMetadata("[" + "0,".repeat(100_001) + "0]"),
                    "{\"error\":{\"message\":\"" + KEY + "\"}}"
            );
            for (String json : invalid) {
                server.reply = exchange -> send(exchange, 200, json);
                fails(IOException.class, () -> backend.infer(request(Duration.ofSeconds(5))));
            }
            for (int status : new int[] {401, 403, 429, 500, 503}) {
                int before = server.requests.size();
                server.reply = exchange -> send(exchange, status, KEY + PROMPT);
                Throwable error = fails(IOException.class, () -> backend.infer(request(Duration.ofSeconds(5))));
                check(error.getMessage().contains(Integer.toString(status)), "HTTP status reported");
                check(server.requests.size() == before + 1, "failed POST not retried");
            }
            int before = server.requests.size();
            server.reply = exchange -> {
                exchange.getResponseHeaders().set("Location", server.base.resolve("leak").toString());
                send(exchange, 307, KEY);
            };
            fails(IOException.class, () -> backend.infer(request(Duration.ofSeconds(5))));
            check(server.requests.size() == before + 1, "redirect not followed");
            server.reply = exchange -> send(exchange, 200, new byte[] {(byte) 0xc3, 0x28});
            fails(IOException.class, () -> backend.infer(request(Duration.ofSeconds(5))));
            server.reply = exchange -> {
                exchange.sendResponseHeaders(200, 0); // Chunked: no Content-Length shortcut.
                exchange.getResponseBody().write(
                        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"".getBytes(StandardCharsets.UTF_8));
                byte[] chunk = new byte[8192];
                java.util.Arrays.fill(chunk, (byte) 'a');
                for (int i = 0; i < GeminiApiBackend.MAX_RESPONSE_BYTES / chunk.length + 1; i++) {
                    exchange.getResponseBody().write(chunk);
                }
                exchange.getResponseBody().write(
                        "\"}]},\"finishReason\":\"STOP\"}]}".getBytes(StandardCharsets.UTF_8));
            };
            fails(IOException.class, () -> backend.infer(request(Duration.ofSeconds(5))));
            before = server.requests.size();
            fails(IOException.class, () -> backend.infer(new ModelRequest("broken\uD800", 1, Duration.ofSeconds(1))));
            check(server.requests.size() == before, "invalid input Unicode rejected before sending");
        }
    }

    @SuppressWarnings("try") // Explicit close calls exercise idempotence and use-after-close.
    private static void deadlineAndLifecycleContracts(Server server) throws Exception {
        CountDownLatch headerRelease = new CountDownLatch(1);
        try (GeminiApiBackend backend = server.backend("low")) {
            server.reply = exchange -> headerRelease.await(5, TimeUnit.SECONDS);
            long start = System.nanoTime();
            fails(TimeoutException.class, () -> backend.infer(request(Duration.ofMillis(150))));
            check(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0,
                    "header deadline bounded");
        } finally {
            headerRelease.countDown();
        }
        CountDownLatch bodyRelease = new CountDownLatch(1);
        CountDownLatch headersSent = new CountDownLatch(1);
        try (GeminiApiBackend backend = server.backend("low")) {
            server.reply = exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                bodyRelease.await(5, TimeUnit.SECONDS);
            };
            long start = System.nanoTime();
            fails(TimeoutException.class, () -> backend.infer(request(Duration.ofMillis(500))));
            check(headersSent.getCount() == 0, "headers and body prefix arrived before body deadline");
            check(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0,
                    "body deadline bounded after headers");
        } finally {
            bodyRelease.countDown();
        }
        pendingTermination(server, false);
        pendingTermination(server, true);
        try (GeminiApiBackend backend = server.backend("high")) {
            int before = server.requests.size();
            Thread.currentThread().interrupt();
            try {
                fails(InterruptedException.class, () -> backend.infer(request(Duration.ofSeconds(1))));
                check(Thread.currentThread().isInterrupted(), "pre-existing interrupt preserved");
            } finally {
                Thread.interrupted();
            }
            check(before == server.requests.size(), "pre-interrupted invocation not sent");
            backend.close();
            backend.close();
            fails(IOException.class, () -> backend.infer(request(Duration.ofSeconds(1))));
            check(before == server.requests.size(), "closed invocation not sent");
        }
    }

    @SuppressWarnings("try") // Explicit close cancels an in-flight operation.
    private static void pendingTermination(Server server, boolean close) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        try (GeminiApiBackend backend = server.backend("high")) {
            server.reply = exchange -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            };
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    backend.infer(request(Duration.ofSeconds(10)));
                    outcome.set(new AssertionError("pending request completed unexpectedly"));
                } catch (Throwable result) {
                    outcome.set(result);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            check(entered.await(3, TimeUnit.SECONDS), "pending request started");
            if (close) backend.close(); else worker.interrupt();
            worker.join(2000);
            check(!worker.isAlive(), "pending request terminated promptly");
            check(close ? outcome.get() instanceof IOException : outcome.get() instanceof InterruptedException,
                    "pending request termination type");
            if (!close) check(interrupted.get(), "in-flight interruption preserved");
            safe(outcome.get());
        } finally {
            release.countDown();
        }
    }

    private static ModelRequest request(Duration timeout) {
        return new ModelRequest(PROMPT, 128, timeout);
    }

    private static String success(String text) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text
                + "\"}]},\"finishReason\":\"STOP\"}]}";
    }

    private static String withMetadata(String value) {
        String response = success("valid candidate");
        return response.substring(0, response.length() - 1) + ",\"metadata\":" + value + "}";
    }

    private static Throwable fails(Class<? extends Throwable> expected, Throwing action) throws Exception {
        try {
            action.run();
        } catch (Throwable failure) {
            if (!expected.isInstance(failure)) throw new AssertionError("expected " + expected.getSimpleName(), failure);
            checks++;
            safe(failure);
            return failure;
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }

    private static void safe(Throwable failure) {
        check(failure != null, "failure present");
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.toString();
            check(!message.contains(KEY) && !message.contains(PROMPT), "no key or prompt in failure chain");
        }
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Throwing { void run() throws Exception; }

    @FunctionalInterface
    private interface Reply { void send(HttpExchange exchange) throws Exception; }

    private record Captured(String method, URI uri, String key, String contentType, String body) { }

    private static final class Server implements AutoCloseable {
        final HttpServer http;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final URI base;
        final List<Captured> requests = new CopyOnWriteArrayList<>();
        volatile Reply reply;

        Server() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/v1beta/");
            http.setExecutor(executor);
            http.createContext("/", exchange -> {
                Reply action = reply;
                try (exchange) {
                    requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI(),
                            exchange.getRequestHeaders().getFirst("x-goog-api-key"),
                            exchange.getRequestHeaders().getFirst("Content-Type"),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    action.send(exchange);
                } catch (Exception closedConnection) {
                    // Deadline, interruption, oversize and close tests deliberately terminate connections.
                }
            });
            http.start();
        }

        GeminiApiBackend backend(String thinking) {
            return new GeminiApiBackend(KEY, "gemini-4-hydrogen", thinking, base);
        }

        @Override
        public void close() {
            http.stop(0);
            executor.shutdownNow();
        }
    }
}
