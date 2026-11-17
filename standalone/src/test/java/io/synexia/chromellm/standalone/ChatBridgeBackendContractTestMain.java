// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/** Executable loopback contracts for the chat-completions bridge; no bridge, browser or model is used. */
public final class ChatBridgeBackendContractTestMain {
    private static final String TOKEN = "contract-token-do-not-echo";
    private static final String PROMPT = "private prompt: \"quoted\"\nनमस्ते 🚀 \\ tail\t";
    private static final String MODEL = "chrome-gemini-nano";
    private static int checks;

    private ChatBridgeBackendContractTestMain() { }

    public static void main(String[] args) throws Exception {
        configurationContracts();
        try (Server server = new Server()) {
            requestAndResponseContracts(server);
            rejectedResponses(server);
            deadlineAndLifecycleContracts(server);
            entrypointContracts(server);
        }
        System.out.println("Chat bridge contract checks passed: " + checks);
    }

    private static void configurationContracts() throws Exception {
        for (String endpoint : List.of("https://127.0.0.1:8080/v1/", "http://example.com/v1/",
                "http://10.0.0.1:8080/v1/", "http://127.0.0.1:8080/v1", "http://127.0.0.1:8080/v1/?key=secret",
                "http://user@127.0.0.1:8080/v1/", "http://127.0.0.1:8080/v1/#fragment",
                "http://127.0.0.1:65536/v1/", "http://127.0.0.1/v1/")) {
            fails(IllegalArgumentException.class, () -> new ChatBridgeBackend(URI.create(endpoint), MODEL, TOKEN));
        }
        fails(IllegalArgumentException.class, () -> new ChatBridgeBackend(null, MODEL, TOKEN));
        for (String model : List.of("", "../other", "bad?key=secret", "models/nano", "bad:model", " nano")) {
            fails(IllegalArgumentException.class, () -> new ChatBridgeBackend(
                    ChatBridgeBackend.DEFAULT_SERVICE, model, TOKEN));
        }
        for (String token : new String[] {" ", "bad\r\nheader", "bad token", "é", "x".repeat(4097), null}) {
            fails(IllegalArgumentException.class, () -> new ChatBridgeBackend(
                    ChatBridgeBackend.DEFAULT_SERVICE, MODEL, token));
        }
        for (String endpoint : List.of("http://127.0.0.1:11435/v1/", "http://[::1]:11435/v1/",
                "http://localhost:11435/")) {
            try (ChatBridgeBackend backend = new ChatBridgeBackend(URI.create(endpoint), MODEL, "")) {
                check(backend.id().equals("chat-bridge:" + MODEL), "loopback endpoint accepted: " + endpoint);
            }
        }
    }

    private static void requestAndResponseContracts(Server server) throws Exception {
        server.reply = exchange -> send(exchange, 200, success("advice text"));
        try (ChatBridgeBackend backend = server.backend(TOKEN)) {
            ModelResponse response = backend.infer(new ModelRequest(PROMPT, 77, Duration.ofSeconds(10)));
            check(response.text().equals("advice text"), "reply text is choices[0].message.content");
            check(response.backendId().equals("chat-bridge:" + MODEL), "backend id names the model");
            check(response.promptSha256().equals(sha256(PROMPT)), "prompt receipt is the SHA-256 of the prompt");
            Captured request = server.requests.getLast();
            check(request.method().equals("POST"), "POST");
            check(request.uri().getPath().equals("/v1/chat/completions"), "chat/completions under the base path");
            check(("Bearer " + TOKEN).equals(request.authorization()), "bearer token header");
            check(request.contentType().startsWith("application/json"), "JSON content type");
            Map<?, ?> body = (Map<?, ?>) GeminiJson.parse(request.body());
            check(MODEL.equals(body.get("model")), "model field");
            check(Boolean.FALSE.equals(body.get("stream")), "not streaming");
            // GeminiJson validates numbers without keeping their value, so the field is checked in the raw body.
            check(body.containsKey("max_tokens") && request.body().contains("\"max_tokens\":77,"), "max_tokens field");
            List<?> messages = (List<?>) body.get("messages");
            Map<?, ?> message = (Map<?, ?>) messages.getFirst();
            check(messages.size() == 1 && "user".equals(message.get("role")), "one user message");
            check(PROMPT.equals(message.get("content")), "prompt bytes survive JSON quoting");
        }
        try (ChatBridgeBackend backend = server.backend("")) {
            backend.infer(new ModelRequest("x", 1, Duration.ofSeconds(10)));
            check(server.requests.getLast().authorization() == null, "empty token sends no Authorization header");
        }
    }

    private static void rejectedResponses(Server server) throws Exception {
        List<String> bodies = List.of("{\"error\":{\"message\":\"" + PROMPT.length() + "\"}}",
                "{\"choices\":[]}", "{\"text\":\"top level\"}", "{\"choices\":[{\"message\":{\"content\":\"  \"}}]}",
                "{\"choices\":[{\"message\":{\"content\":7}}]}", "{\"choices\":[{\"text\":\"legacy\"}]}",
                "[1,2]", "not json");
        try (ChatBridgeBackend backend = server.backend(TOKEN)) {
            for (String body : bodies) {
                server.reply = exchange -> send(exchange, 200, body);
                fails(IOException.class, () -> backend.infer(new ModelRequest(PROMPT, 8, Duration.ofSeconds(10))));
            }
            server.reply = exchange -> send(exchange, 500, "{\"detail\":\"" + TOKEN + "\"}");
            Throwable status = fails(IOException.class,
                    () -> backend.infer(new ModelRequest(PROMPT, 8, Duration.ofSeconds(10))));
            check(status.getMessage().equals("chat bridge returned HTTP status 500"), "status only, no body");
            byte[] huge = new byte[ChatBridgeBackend.MAX_RESPONSE_BYTES + 1];
            server.reply = exchange -> send(exchange, 200, huge);
            Throwable large = fails(IOException.class,
                    () -> backend.infer(new ModelRequest(PROMPT, 8, Duration.ofSeconds(20))));
            check(large.getMessage().contains("bounded limit"), "oversized response refused");
        }
    }

    private static void deadlineAndLifecycleContracts(Server server) throws Exception {
        server.reply = exchange -> {
            Thread.sleep(3000);
            send(exchange, 200, success("late"));
        };
        try (ChatBridgeBackend backend = server.backend(TOKEN)) {
            fails(TimeoutException.class, () -> backend.infer(new ModelRequest(PROMPT, 8, Duration.ofMillis(300))));
        }
        server.reply = exchange -> send(exchange, 200, success("unused"));
        ChatBridgeBackend closed = server.backend(TOKEN);
        closed.close();
        int before = server.requests.size();
        fails(IOException.class, () -> closed.infer(new ModelRequest(PROMPT, 8, Duration.ofSeconds(5))));
        check(server.requests.size() == before, "a closed backend sends nothing");
        try (ChatBridgeBackend unreachable = new ChatBridgeBackend(URI.create("http://127.0.0.1:1/v1/"), MODEL, TOKEN)) {
            Throwable transport = fails(IOException.class,
                    () -> unreachable.infer(new ModelRequest(PROMPT, 8, Duration.ofSeconds(5))));
            check(transport.getMessage().equals("chat bridge transport failed") && transport.getCause() == null,
                    "transport failure carries no cause");
        }
    }

    private static void entrypointContracts(Server server) throws Exception {
        Path folder = Files.createTempDirectory("chat-bridge-contract");
        Path tokenFile = folder.resolve("bridge.token");
        Files.writeString(tokenFile, "  " + TOKEN + "\n", StandardCharsets.US_ASCII);
        server.reply = exchange -> send(exchange, 200, success("from the entrypoint"));
        try (ModelBackend backend = StandaloneMain.backend(StandaloneMain.parse(new String[] {
                "--backend", "bridge", "--bridge-url", server.base.toString(),
                "--bridge-token-file", tokenFile.toString()}), Map.of())) {
            check(backend instanceof ChatBridgeBackend, "bridge selects the chat bridge");
            check(backend.id().equals("chat-bridge:" + ChatBridgeBackend.DEFAULT_MODEL), "default model");
            check(backend.infer(new ModelRequest("x", 4, Duration.ofSeconds(10))).text()
                    .equals("from the entrypoint"), "entrypoint backend answers");
            check(("Bearer " + TOKEN).equals(server.requests.getLast().authorization()), "token file stripped");
        }
        Path empty = folder.resolve("empty.token");
        Files.writeString(empty, " \n", StandardCharsets.US_ASCII);
        fails(IllegalArgumentException.class, () -> StandaloneMain.backend(StandaloneMain.parse(new String[] {
                "--backend", "bridge", "--bridge-token-file", empty.toString()}), Map.of()));
        fails(IllegalArgumentException.class, () -> StandaloneMain.backend(StandaloneMain.parse(new String[] {
                "--backend", "bridge", "--bridge-token-file", folder.resolve("missing").toString()}), Map.of()));
        fails(IllegalArgumentException.class, () -> StandaloneMain.backend(StandaloneMain.parse(new String[] {
                "--backend", "bridge", "--llama-bin", "llama-cli"}), Map.of()));
        fails(IllegalArgumentException.class, () -> StandaloneMain.backend(StandaloneMain.parse(new String[] {
                "--backend", "bridge", "--api-key-env", "GEMINI_API_KEY"}), Map.of()));
        fails(IllegalArgumentException.class, () -> StandaloneMain.backend(StandaloneMain.parse(new String[] {
                "--backend", "gemini", "--bridge-url", server.base.toString()}), Map.of("GEMINI_API_KEY", "k")));
        fails(IllegalArgumentException.class, () -> StandaloneMain.backend(StandaloneMain.parse(new String[] {
                "--backend", "cli", "--bridge-token-file", tokenFile.toString()}), Map.of()));
        Files.delete(empty);
        Files.delete(tokenFile);
        Files.delete(folder);
    }

    private static String success(String text) {
        return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"model\":\"" + MODEL
                + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + text
                + "\"},\"finish_reason\":\"stop\"}]}";
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Throwable fails(Class<? extends Throwable> expected, Throwing action) throws Exception {
        try {
            action.run();
        } catch (Throwable failure) {
            if (!expected.isInstance(failure)) throw new AssertionError("expected " + expected.getSimpleName(), failure);
            checks++;
            for (Throwable current = failure; current != null; current = current.getCause()) {
                String message = current.toString();
                check(!message.contains(TOKEN) && !message.contains(PROMPT), "no token or prompt in failure chain");
            }
            return failure;
        }
        throw new AssertionError("expected " + expected.getSimpleName());
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

    private record Captured(String method, URI uri, String authorization, String contentType, String body) { }

    private static final class Server implements AutoCloseable {
        final HttpServer http;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final URI base;
        final List<Captured> requests = new CopyOnWriteArrayList<>();
        volatile Reply reply;

        Server() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/v1/");
            http.setExecutor(executor);
            http.createContext("/", exchange -> {
                Reply action = reply;
                try (exchange) {
                    requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI(),
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            exchange.getRequestHeaders().getFirst("Content-Type"),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    action.send(exchange);
                } catch (Exception closedConnection) {
                    // Deadline and oversize tests deliberately terminate connections.
                }
            });
            http.start();
        }

        ChatBridgeBackend backend(String token) {
            return new ChatBridgeBackend(base, MODEL, token);
        }

        @Override
        public void close() {
            http.stop(0);
            executor.shutdownNow();
        }
    }
}
