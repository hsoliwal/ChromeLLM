// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Explicit remote text provider; it never selects or falls back to a local model. */
final class GeminiApiBackend implements ModelBackend {
    static final String DEFAULT_MODEL = "gemini-4-hydrogen";
    static final String DEFAULT_THINKING_LEVEL = "high";
    static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final URI SERVICE = URI.create("https://generativelanguage.googleapis.com/v1beta/");
    private static final Set<String> TEXT_PART_FIELDS = Set.of(
            "text", "thought", "thoughtSignature", "partMetadata");
    private final String apiKey;
    private final String model;
    private final String thinkingLevel;
    private final URI endpoint;
    private final HttpClient client;
    private final Set<CompletableFuture<?>> active = new HashSet<>();
    private boolean closed;

    GeminiApiBackend(String apiKey, String model, String thinkingLevel) {
        this(apiKey, model, thinkingLevel, SERVICE);
    }

    /** The extra endpoint is an internal loopback test seam, never a CLI option. */
    GeminiApiBackend(String apiKey, String model, String thinkingLevel, URI service) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 4096
                || apiKey.chars().anyMatch(c -> c <= 32 || c >= 127)) {
            throw new IllegalArgumentException("Gemini API key must be nonblank printable ASCII without whitespace");
        }
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("invalid Gemini model identifier");
        }
        String level = Objects.requireNonNull(thinkingLevel, "thinkingLevel").toUpperCase(Locale.ROOT);
        if (!Set.of("LOW", "MEDIUM", "HIGH", "XHIGH").contains(level)) {
            throw new IllegalArgumentException("thinking level must be low, medium, high or xhigh");
        }
        validateService(service);
        this.apiKey = apiKey;
        this.model = model;
        this.thinkingLevel = level;
        this.endpoint = service.resolve("models/" + model + ":generateContent");
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30)).build();
    }

    private static void validateService(URI service) {
        if (service == null || service.getRawUserInfo() != null || service.getRawQuery() != null
                || service.getRawFragment() != null || !"/v1beta/".equals(service.getRawPath())) {
            throw new IllegalArgumentException("invalid Gemini service endpoint");
        }
        boolean production = SERVICE.equals(service);
        boolean loopback = "http".equals(service.getScheme())
                && ("127.0.0.1".equals(service.getHost()) || "[::1]".equals(service.getHost()))
                && service.getPort() > 0 && service.getPort() <= 65535;
        if (!production && !loopback) {
            throw new IllegalArgumentException("Gemini service must be the official HTTPS endpoint or a loopback test endpoint");
        }
    }

    @Override
    public String id() {
        return "gemini-api:" + model;
    }

    @Override
    public ModelResponse infer(ModelRequest request) throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(request, "request");
        checkInterrupted();
        long deadline = System.nanoTime() + request.timeout().toNanos();
        synchronized (active) {
            if (closed) throw new IOException("Gemini backend is closed");
        }
        String prefix = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":";
        String suffix = "}]}],\"generationConfig\":{\"candidateCount\":1,\"maxOutputTokens\":"
                + request.maxTokens() + ",\"responseModalities\":[\"TEXT\"],\"thinkingConfig\":{"
                + "\"thinkingLevel\":\"" + thinkingLevel + "\",\"includeThoughts\":false}}}";
        // All envelope characters are ASCII. The codec checks UTF-8 and escaped size before allocation.
        String body = prefix + GeminiJson.quote(request.prompt(),
                MAX_REQUEST_BYTES - prefix.length() - suffix.length()) + suffix;
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofNanos(remaining(deadline)))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        CompletableFuture<HttpResponse<byte[]>> pending;
        synchronized (active) {
            if (closed) throw new IOException("Gemini backend is closed");
            pending = client.sendAsync(httpRequest, info -> new BoundedBodySubscriber());
            active.add(pending);
        }
        try {
            HttpResponse<byte[]> response = pending.get(remaining(deadline), TimeUnit.NANOSECONDS);
            checkInterrupted();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // Do not propagate an error body, headers, URI, key, or transport exception cause.
                throw new IOException("Gemini API returned HTTP status " + response.statusCode());
            }
            String text = responseText(decodeUtf8(response.body()));
            ModelResponse result = new ModelResponse(text, id(), sha256(request.prompt()));
            remaining(deadline);
            checkInterrupted();
            synchronized (active) {
                if (closed) throw new IOException("Gemini backend is closed");
            }
            return result;
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new TimeoutException("Gemini inference exceeded configured deadline");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedException("Gemini inference interrupted");
        } catch (CancellationException cancelled) {
            throw new IOException("Gemini inference cancelled or backend closed");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof HttpTimeoutException) {
                throw new TimeoutException("Gemini inference exceeded configured deadline");
            }
            throw new IOException("Gemini transport failed or response exceeded the bounded limit");
        } finally {
            synchronized (active) {
                active.remove(pending);
            }
        }
    }

    @Override
    public void close() {
        List<CompletableFuture<?>> pending;
        synchronized (active) {
            if (closed) return;
            closed = true;
            pending = List.copyOf(active);
            active.clear();
        }
        for (CompletableFuture<?> future : pending) future.cancel(true);
        client.shutdownNow();
    }

    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("Gemini inference exceeded configured deadline");
        return remaining;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Gemini inference interrupted");
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("Gemini response contains invalid UTF-8");
        }
    }

    private static String responseText(String json) throws IOException {
        Map<?, ?> response = object(GeminiJson.parse(json));
        if (response.containsKey("error")) throw new IOException("Gemini returned an API error");
        if (response.containsKey("promptFeedback")) {
            Map<?, ?> feedback = object(response.get("promptFeedback"));
            if (feedback.containsKey("blockReason")
                    && !"BLOCK_REASON_UNSPECIFIED".equals(feedback.get("blockReason"))) {
                throw new IOException("Gemini blocked the prompt");
            }
            checkSafety(feedback);
        }
        List<?> candidates = array(response.get("candidates"));
        if (candidates.size() != 1) throw new IOException("Gemini did not return one text candidate");
        Map<?, ?> candidate = object(candidates.getFirst());
        if ("MAX_TOKENS".equals(candidate.get("finishReason"))) {
            throw new IOException("Gemini reached the token budget; increase max tokens or lower the thinking level");
        }
        if (!"STOP".equals(candidate.get("finishReason"))) {
            throw new IOException("Gemini did not return a completed text candidate");
        }
        checkSafety(candidate);
        Map<?, ?> content = object(candidate.get("content"));
        if (content.containsKey("role") && !"model".equals(content.get("role"))) {
            throw new IOException("Gemini response has an invalid content role");
        }
        StringBuilder text = new StringBuilder();
        for (Object item : array(content.get("parts"))) {
            Map<?, ?> part = object(item);
            if (part.containsKey("thought") && !(part.get("thought") instanceof Boolean)) {
                throw new IOException("Gemini response has an invalid thought flag");
            }
            if (Boolean.TRUE.equals(part.get("thought"))) continue;
            if (!TEXT_PART_FIELDS.containsAll(part.keySet()) || !(part.get("text") instanceof String value)) {
                throw new IOException("Gemini returned a non-text part");
            }
            text.append(value);
        }
        if (text.toString().isBlank()) throw new IOException("Gemini returned no candidate text");
        return text.toString();
    }

    private static void checkSafety(Map<?, ?> parent) throws IOException {
        if (!parent.containsKey("safetyRatings")) return;
        for (Object item : array(parent.get("safetyRatings"))) {
            Map<?, ?> rating = object(item);
            if (rating.containsKey("blocked") && !Boolean.FALSE.equals(rating.get("blocked"))) {
                throw new IOException("Gemini blocked the candidate");
            }
        }
    }

    private static Map<?, ?> object(Object value) throws IOException {
        if (value instanceof Map<?, ?> object) return object;
        throw new IOException("Gemini response has an invalid object");
    }

    private static List<?> array(Object value) throws IOException {
        if (value instanceof List<?> array) return array;
        throw new IOException("Gemini response has an invalid array");
    }

    private static String sha256(String prompt) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(prompt.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /** Completion occurs only after the entire bounded body, so the deadline covers slow bodies. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription next) {
            if (subscription != null) {
                next.cancel();
                return;
            }
            subscription = next;
            next.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Gemini response exceeded the bounded limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(new IOException("Gemini response body failed"));
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
