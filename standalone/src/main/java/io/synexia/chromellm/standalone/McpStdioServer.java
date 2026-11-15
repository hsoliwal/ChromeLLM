// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** Opt-in MCP JSON-RPC stdio server, separate from the existing CLM1 binary protocol. */
final class McpStdioServer {
    static final String VERSION = "2025-06-18";
    private static final int MAX_INPUT = StdioProtocol.MAX_PROMPT_BYTES + 2048;
    private static final int MAX_OUTPUT = StdioProtocol.MAX_RESPONSE_BYTES;
    private final ModelBackend backend;
    private boolean negotiated;
    private boolean initialized;

    private McpStdioServer(ModelBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /** Caller owns model, input and output streams. All processing is sequential. */
    static void serve(ModelBackend backend, InputStream input, OutputStream output) throws IOException {
        McpStdioServer session = new McpStdioServer(backend);
        BufferedInputStream incoming = new BufferedInputStream(input);
        while (true) {
            String line = readLine(incoming);
            if (line == null) return;
            String reply = session.process(line);
            if (reply != null) {
                byte[] bytes = StdioProtocol.encode(reply, MAX_OUTPUT);
                output.write(bytes);
                output.write('\n');
                output.flush();
            }
        }
    }

    /** Package-private so synthetic contract tests cover wire behavior exactly. */
    String process(String line) throws IOException {
        final Object parsed;
        try { parsed = McpJson.parse(line); }
        catch (IOException malformed) { return encoded(error(null, -32700, "Parse error")); }
        if (!(parsed instanceof Map<?, ?> raw)) return encoded(error(null, -32600, "Invalid Request"));
        Map<String, Object> message = map(raw);
        Object id = message.get("id");
        if (message.containsKey("id") && !validId(id)) return encoded(error(null, -32600, "Invalid Request"));
        if (!"2.0".equals(message.get("jsonrpc")) || !(message.get("method") instanceof String method)) {
            return encoded(error(validId(id) ? id : null, -32600, "Invalid Request"));
        }
        boolean notification = !message.containsKey("id");
        if (notification) {
            if ("notifications/initialized".equals(method) && negotiated) initialized = true;
            return null;
        }
        if ("initialize".equals(method)) {
            if (negotiated || !(message.get("params") instanceof Map<?, ?> params)
                    || !(params.get("protocolVersion") instanceof String)
                    || !(params.get("capabilities") instanceof Map<?, ?>)
                    || !(params.get("clientInfo") instanceof Map<?, ?>)) {
                return encoded(error(id, -32602, "Invalid initialization parameters"));
            }
            negotiated = true;
            return encoded(success(id, Map.of(
                    "protocolVersion", VERSION,
                    "capabilities", Map.of("tools", Map.of("listChanged", false)),
                    "serverInfo", Map.of("name", "chromellm-standalone", "version", "0.1.0"))));
        }
        if (!initialized) return encoded(error(id, -32000, "MCP session not initialized"));
        if ("ping".equals(method)) return encoded(success(id, Map.of()));
        if ("tools/list".equals(method)) return encoded(success(id, Map.of("tools", toolList())));
        if (!"tools/call".equals(method)) return encoded(error(id, -32601, "Method not found"));
        if (!(message.get("params") instanceof Map<?, ?> params)) {
            return encoded(error(id, -32602, "Invalid tool call parameters"));
        }
        if (!(params.get("name") instanceof String tool)) {
            return encoded(error(id, -32602, "Tool name is required"));
        }
        Object args = params.containsKey("arguments") ? params.get("arguments") : Map.of();
        if (!(args instanceof Map<?, ?> arguments)) {
            return encoded(error(id, -32602, "Tool arguments must be an object"));
        }
        if ("backend_status".equals(tool)) {
            if (!arguments.isEmpty()) return encoded(error(id, -32602, "Unknown status arguments"));
            return encoded(success(id, toolResult(backend.id(), false)));
        }
        if (!"infer_text".equals(tool)) return encoded(error(id, -32602, "Unknown tool"));
        if (!(arguments.get("prompt") instanceof String prompt) || prompt.isBlank()
                || arguments.keySet().stream().anyMatch(k -> !List.of("prompt", "maxTokens", "timeoutSeconds").contains(k))) {
            return encoded(error(id, -32602, "Invalid inference arguments"));
        }
        int maxTokens;
        int timeoutSeconds;
        try {
            maxTokens = integer(arguments, "maxTokens", 512, 1, 32768);
            timeoutSeconds = integer(arguments, "timeoutSeconds", 120, 1, 3600);
            StdioProtocol.encode(prompt, StdioProtocol.MAX_PROMPT_BYTES);
        } catch (IOException | IllegalArgumentException invalid) {
            return encoded(error(id, -32602, "Invalid inference arguments"));
        }
        try {
            ModelResponse result = backend.infer(new ModelRequest(prompt, maxTokens,
                    Duration.ofSeconds(timeoutSeconds)));
            if (result == null || !backend.id().equals(result.backendId())) {
                throw new IOException("invalid selected backend identity");
            }
            String response = encoded(success(id, toolResult(result.text(), false)));
            return response;
        } catch (IOException | TimeoutException | IllegalArgumentException failure) {
            return encoded(success(id, toolResult("Model inference failed", true)));
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return encoded(success(id, toolResult("Model inference interrupted", true)));
        }
    }

    private static int integer(Map<?, ?> arguments, String key, int fallback, int min, int max) {
        if (!arguments.containsKey(key)) return fallback;
        Object value = arguments.get(key);
        if (!(value instanceof BigDecimal number)) throw new IllegalArgumentException("integer required");
        int result = number.intValueExact();
        if (result < min || result > max) throw new IllegalArgumentException("integer outside range");
        return result;
    }

    private static List<Map<String, Object>> toolList() {
        return List.of(
                Map.of("name", "backend_status", "description", "Read the explicitly selected inference provider ID without loading the model.",
                        "inputSchema", Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                        "annotations", Map.of("readOnlyHint", true)),
                Map.of("name", "infer_text", "description", "Run one explicit text inference using the configured provider; model outputs are untrusted candidate text.",
                        "inputSchema", Map.of("type", "object", "additionalProperties", false,
                                "properties", Map.of("prompt", Map.of("type", "string"),
                                        "maxTokens", Map.of("type", "integer", "minimum", 1, "maximum", 32768),
                                        "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 3600)),
                                "required", List.of("prompt"))));
    }

    private static Map<String, Object> toolResult(String text, boolean error) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", error);
    }

    private static Map<String, Object> success(Object id, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jsonrpc", "2.0");
        result.put("id", id);
        result.put("result", value);
        return result;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jsonrpc", "2.0");
        result.put("id", id);
        result.put("error", Map.of("code", code, "message", message));
        return result;
    }

    private static String encoded(Object object) throws IOException {
        return McpJson.encode(object, MAX_OUTPUT);
    }

    private static Map<String, Object> map(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> { if (k instanceof String key) result.put(key, v); });
        return result;
    }

    private static boolean validId(Object value) {
        if (value instanceof String) return true;
        if (value instanceof BigDecimal number) {
            // JSON-RPC integer IDs are bounded to signed 64-bit to avoid enormous exponents.
            if (number.precision() > 19 || number.scale() < -18 || number.scale() > 18) return false;
            try { number.longValueExact(); return true; }
            catch (ArithmeticException invalid) { return false; }
        }
        return false;
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int b = input.read();
            if (b < 0) {
                if (bytes.size() == 0) return null;
                throw new IOException("truncated MCP message (missing LF)");
            }
            if (b == '\n') break;
            if (bytes.size() >= MAX_INPUT) throw new IOException("MCP input exceeds bounded message size");
            bytes.write(b);
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }
}
