// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** Loopback OpenAI-compatible chat-completions provider, for example a local Chrome on-device model bridge. */
final class ChatBridgeBackend implements ModelBackend {
    static final String DEFAULT_MODEL = "chrome-gemini-nano";
    static final URI DEFAULT_SERVICE = URI.create("http://127.0.0.1:11435/v1/");
    static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "[::1]", "localhost");
    private final URI endpoint;
    private final String model;
    private final String token;
    private final HttpClient client;
    private volatile boolean closed;

    /** An empty token sends no Authorization header. */
    ChatBridgeBackend(URI service, String model, String token) {
        if (service == null || !"http".equals(service.getScheme()) || !LOOPBACK_HOSTS.contains(service.getHost())
                || service.getPort() < 1 || service.getPort() > 65535 || service.getRawUserInfo() != null
                || service.getRawQuery() != null || service.getRawFragment() != null
                || service.getRawPath() == null || !service.getRawPath().endsWith("/")) {
            throw new IllegalArgumentException("chat bridge must be a loopback http endpoint ending with /");
        }
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("invalid chat bridge model identifier");
        }
        if (token == null || token.length() > 4096 || token.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("chat bridge token must be printable ASCII without whitespace");
        }
        this.endpoint = service.resolve("chat/completions");
        this.model = model;
        this.token = token;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String id() {
        return "chat-bridge:" + model;
    }

    @Override
    public ModelResponse infer(ModelRequest request) throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(request, "request");
        if (closed) throw new IOException("chat bridge backend is closed");
        String body = "{\"model\":" + GeminiJson.quote(model, 256)
                + ",\"messages\":[{\"role\":\"user\",\"content\":"
                + GeminiJson.quote(request.prompt(), MAX_REQUEST_BYTES - 512)
                + "}],\"max_tokens\":" + request.maxTokens() + ",\"stream\":false}";
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(request.timeout())
                .header("Content-Type", "application/json; charset=utf-8").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException timeout) {
            throw new TimeoutException("chat bridge exceeded the configured deadline");
        } catch (IOException failed) {
            // Neither the cause nor its message: either could carry the URI or headers.
            throw new IOException("chat bridge transport failed");
        }
        byte[] bytes;
        try (InputStream in = response.body()) {
            bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
        }
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new IOException("chat bridge response exceeded the bounded limit");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("chat bridge returned HTTP status " + response.statusCode());
        }
        String text = responseText(new String(bytes, StandardCharsets.UTF_8));
        return new ModelResponse(text, id(), sha256(request.prompt()));
    }

    @Override
    public void close() {
        closed = true;
        client.shutdownNow();
    }

    private static String responseText(String json) throws IOException {
        Map<?, ?> root = object(GeminiJson.parse(json));
        if (root.containsKey("error")) throw new IOException("chat bridge returned an API error");
        List<?> choices = array(root.get("choices"));
        if (choices.isEmpty()) throw new IOException("chat bridge returned no choices");
        Map<?, ?> message = object(object(choices.getFirst()).get("message"));
        if (!(message.get("content") instanceof String text) || text.isBlank()) {
            throw new IOException("chat bridge returned no text");
        }
        return text;
    }

    private static Map<?, ?> object(Object value) throws IOException {
        if (value instanceof Map<?, ?> map) return map;
        throw new IOException("chat bridge response has an invalid object");
    }

    private static List<?> array(Object value) throws IOException {
        if (value instanceof List<?> list) return list;
        throw new IOException("chat bridge response has an invalid array");
    }

    private static String sha256(String prompt) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(prompt.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
