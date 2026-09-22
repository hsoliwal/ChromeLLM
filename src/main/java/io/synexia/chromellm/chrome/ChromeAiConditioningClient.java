package io.synexia.chromellm.chrome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.synexia.chromellm.api.DiffusionCondition;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class ChromeAiConditioningClient {
    private static final String SYSTEM = """
            Convert the user's visual intent into compact JSON only.
            Schema: {"class_id":integer,"vector":[number,...]}.
            vector must contain 16 values in [0,1] describing palette, lighting,
            composition, depth, texture, saturation, contrast, and atmosphere.
            No markdown and no prose.
            """;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI endpoint;

    public ChromeAiConditioningClient(URI baseUri) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.endpoint = URI.create(baseUri.toString().replaceAll("/+$", "") + "/v1/chat/completions");
    }

    public DiffusionCondition plan(String prompt) {
        try {
            var messages = mapper.createArrayNode()
                    .add(mapper.createObjectNode().put("role","system").put("content",SYSTEM))
                    .add(mapper.createObjectNode().put("role","user").put("content",prompt));
            var body = mapper.createObjectNode().put("model","chrome-gemini-nano").put("temperature",0.2).set("messages",messages).toString();
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMinutes(2))
                    .header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("ChromeLLM HTTP " + response.statusCode() + ": " + response.body());
            String content = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content").asText();
            JsonNode plan = mapper.readTree(stripFence(content));
            List<Float> values = new ArrayList<>();
            for (JsonNode value : plan.path("vector")) values.add((float)Math.max(0d, Math.min(1d, value.asDouble())));
            if (values.isEmpty()) throw new IllegalStateException("ChromeLLM returned an empty conditioning vector");
            float[] vector = new float[values.size() + 1];
            vector[0] = (plan.path("class_id").asInt(0) & 0xFFFF) / 65535f;
            for (int i = 0; i < values.size(); i++) vector[i + 1] = values.get(i);
            return DiffusionCondition.vector(vector);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call ChromeLLM", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling ChromeLLM", e);
        }
    }

    private static String stripFence(String content) {
        String fence = String.valueOf((char)96).repeat(3);
        String value = content.trim();
        if (value.startsWith(fence)) {
            int firstNewline = value.indexOf('\n');
            if (firstNewline >= 0) value = value.substring(firstNewline + 1);
            if (value.endsWith(fence)) value = value.substring(0, value.length() - fence.length());
        }
        return value.trim();
    }
}
