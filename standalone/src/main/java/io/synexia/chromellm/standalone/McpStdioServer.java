// SPDX-License-Identifier: MIT
// Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project.
// Agent additions: Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project; Apache-2.0.
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
    private final List<AgentSession.Tool> sessionAgentTools;
    private final AgentSession.Limits agentLimits;
    private final List<AgentTool> agentTools;
    private boolean negotiated;
    private boolean initialized;

    private McpStdioServer(ModelBackend backend, List<AgentSession.Tool> sessionAgentTools,
                           AgentSession.Limits agentLimits, List<AgentTool> agentTools) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.sessionAgentTools = List.copyOf(sessionAgentTools);
        this.agentLimits = Objects.requireNonNull(agentLimits, "agentLimits");
        this.agentTools = agentTools == null ? null : List.copyOf(agentTools);
    }

    /** Caller owns model, input and output streams. All processing is sequential. */
    static void serve(ModelBackend backend, InputStream input, OutputStream output) throws IOException {
        serve(backend, input, output, List.of(), AgentSession.Limits.defaults());
    }

    /** A configured tool set adds one bounded agent entry point; the caller owns all resources. */
    static void serve(ModelBackend backend, InputStream input, OutputStream output,
                      List<AgentSession.Tool> agentTools, AgentSession.Limits limits) throws IOException {
        serveSession(new McpStdioServer(backend, agentTools, limits, null), input, output);
    }

    /** Workspace access is explicitly enabled by the process owner, never by a model argument. */
    static void serve(ModelBackend backend, InputStream input, OutputStream output, Path workspace) throws IOException {
        try (WorkspaceTools selected = workspace == null ? null : new WorkspaceTools(workspace)) {
            serveTools(backend, input, output, selected == null ? null : selected.tools());
        }
    }

    static void serveTools(ModelBackend backend, InputStream input, OutputStream output, List<AgentTool> tools)
            throws IOException {
        serveSession(new McpStdioServer(backend, List.of(), AgentSession.Limits.defaults(), tools), input, output);
    }

    /** Independently configured workspace and endpoint capabilities share one bounded MCP stream. */
    static void serve(ModelBackend backend, InputStream input, OutputStream output,
                      List<AgentTool> workspaceTools, List<AgentSession.Tool> endpointTools,
                      AgentSession.Limits limits) throws IOException {
        serveSession(new McpStdioServer(backend, endpointTools, limits, workspaceTools), input, output);
    }

    private static void serveSession(McpStdioServer session, InputStream input, OutputStream output)
            throws IOException {
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
        if (message.containsKey("id") && (!validId(id) || !echoableId(id))) {
            return encoded(error(null, -32600, "Invalid Request"));
        }
        if (!"2.0".equals(message.get("jsonrpc")) || !(message.get("method") instanceof String method)) {
            return encoded(error(validId(id) ? id : null, -32600, "Invalid Request"));
        }
        try {
            return dispatch(message, id, method);
        } catch (IOException responseFailure) {
            // The request ID was admitted against this exact error envelope before dispatch.
            return encoded(error(id, -32603, "MCP response exceeds bounded size"));
        }
    }

    private String dispatch(Map<String, Object> message, Object id, String method) throws IOException {
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
            String reply = encoded(success(id, Map.of(
                    "protocolVersion", VERSION,
                    "capabilities", Map.of("tools", Map.of("listChanged", false)),
                    "serverInfo", Map.of("name", "chromellm-standalone", "version", "0.1.0"))));
            negotiated = true;
            return reply;
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
            return encoded(success(id, backendStatus()));
        }
        if ("plan_steps".equals(tool)) {
            if (!(arguments.get("task") instanceof String task) || task.isBlank()
                    || arguments.keySet().stream().anyMatch(k -> !List.of("task", "maxTokens", "timeoutSeconds").contains(k))) {
                return encoded(error(id, -32602, "Invalid planning arguments"));
            }
            int maxTokens;
            int timeoutSeconds;
            try {
                StdioProtocol.encode(task, AgentPlanner.MAX_TASK_BYTES);
                maxTokens = integer(arguments, "maxTokens", 1024, 1, 32768);
                timeoutSeconds = integer(arguments, "timeoutSeconds", 120, 1, 3600);
            } catch (IOException | IllegalArgumentException invalid) {
                return encoded(error(id, -32602, "Invalid planning arguments"));
            }
            try {
                AgentPlan plan = AgentPlanner.plan(backend, task, maxTokens, Duration.ofSeconds(timeoutSeconds));
                Map<String, Object> result = new LinkedHashMap<>(toolResult(plan.json(), false));
                result.put("structuredContent", plan.structuredContent());
                return encoded(success(id, result));
            } catch (IOException | TimeoutException | RuntimeException failure) {
                return encoded(success(id, toolResult("Agent planning failed", true)));
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                return encoded(success(id, toolResult("Agent planning interrupted", true)));
            }
        }
        if ("run_agent".equals(tool) && !sessionAgentTools.isEmpty()) {
            return runSessionAgent(id, arguments);
        }
        if ("agent_run".equals(tool) && agentTools != null) return runAgent(id, arguments);
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
        } catch (IOException | TimeoutException | RuntimeException failure) {
            return encoded(success(id, toolResult("Model inference failed", true)));
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return encoded(success(id, toolResult("Model inference interrupted", true)));
        }
    }

    private String runSessionAgent(Object id, Map<?, ?> arguments) throws IOException {
        if (!(arguments.get("goal") instanceof String goal) || goal.isBlank()
                || arguments.keySet().stream().anyMatch(key -> !List.of(
                        "goal", "maxSteps", "maxTokens", "timeoutSeconds").contains(key))) {
            return encoded(error(id, -32602, "Invalid agent arguments"));
        }
        final AgentSession.Limits selected;
        try {
            StdioProtocol.encode(goal, agentLimits.maxContextBytes());
            int seconds = Math.toIntExact(agentLimits.timeout().toSeconds());
            selected = new AgentSession.Limits(
                    integer(arguments, "maxSteps", agentLimits.maxSteps(), 1, agentLimits.maxSteps()),
                    integer(arguments, "maxTokens", agentLimits.maxTokens(), 1, agentLimits.maxTokens()),
                    agentLimits.maxContextBytes(), agentLimits.maxToolResultBytes(),
                    Duration.ofSeconds(integer(arguments, "timeoutSeconds", seconds, 1, seconds)));
        } catch (IOException | IllegalArgumentException | ArithmeticException invalid) {
            return encoded(error(id, -32602, "Invalid agent arguments or operator budget exceeded"));
        }
        try {
            AgentSession.Result result = new AgentSession(backend, sessionAgentTools, selected).run(goal);
            Map<String, Object> response = new LinkedHashMap<>(toolResult(result.answer(), false));
            response.put("structuredContent", result.structured());
            return encoded(success(id, response));
        } catch (AgentSession.FailureException refusal) {
            return encoded(success(id, toolResult("Agent stopped: " + refusal.code().name(), true)));
        } catch (TimeoutException timeout) {
            return encoded(success(id, toolResult("Agent deadline exceeded", true)));
        } catch (IOException | RuntimeException failure) {
            return encoded(success(id, toolResult("Agent model or tool failed", true)));
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return encoded(success(id, toolResult("Agent interrupted", true)));
        }
    }

    private String runAgent(Object id, Map<?, ?> arguments) throws IOException {
        if (!(arguments.get("goal") instanceof String goal) || goal.isBlank()
                || arguments.keySet().stream().anyMatch(key -> !List.of(
                        "goal", "maxTokens", "timeoutSeconds", "maxSteps", "maxModelCalls").contains(key))) {
            return encoded(error(id, -32602, "Invalid agent arguments"));
        }
        final int maxTokens;
        final AgentEngine.Limits limits;
        try {
            maxTokens = integer(arguments, "maxTokens", 1024, 1, 32768);
            int seconds = integer(arguments, "timeoutSeconds", 120, 1, 3600);
            limits = new AgentEngine.Limits(integer(arguments, "maxSteps", 16, 1, 128),
                    integer(arguments, "maxModelCalls", 8, 1, 64), 65536, 32768, Duration.ofSeconds(seconds));
            StdioProtocol.encode(goal, StdioProtocol.MAX_PROMPT_BYTES);
        } catch (IOException | IllegalArgumentException | ArithmeticException invalid) {
            return encoded(error(id, -32602, "Invalid agent arguments"));
        }
        try {
            AgentEngine.Result run = new AgentEngine(backend, agentTools, event -> { }).run(goal, maxTokens, limits);
            Map<String, Object> result = new LinkedHashMap<>(toolResult(run.answer(), false));
            result.put("structuredContent", run.asMap());
            return encoded(success(id, result));
        } catch (IOException | TimeoutException | RuntimeException failure) {
            return encoded(success(id, toolResult("Agent run failed", true)));
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return encoded(success(id, toolResult("Agent run interrupted", true)));
        }
    }

    private static int integer(Map<?, ?> arguments, String key, int fallback, int min, int max) {
        if (!arguments.containsKey(key)) return fallback;
        Object value = arguments.get(key);
        if (!(value instanceof BigDecimal number)) throw new IllegalArgumentException("integer required");
        if (number.compareTo(BigDecimal.valueOf(min)) < 0 || number.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new IllegalArgumentException("integer outside range");
        }
        try { return number.intValueExact(); }
        catch (ArithmeticException fractional) {
            throw new IllegalArgumentException("integer required", fractional);
        }
    }

    private List<Map<String, Object>> toolList() {
        List<Map<String, Object>> tools = new ArrayList<>(List.of(
                Map.of("name", "plan_steps",
                        "description", "Propose a bounded dependency-ordered read-only tool plan; does not execute any steps.",
                        "inputSchema", Map.of("type", "object", "additionalProperties", false,
                                "properties", Map.of("task", Map.of("type", "string", "minLength", 1, "maxLength", 4096),
                                        "maxTokens", Map.of("type", "integer", "minimum", 1, "maximum", 32768),
                                        "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 3600)),
                                "required", List.of("task")),
                        "annotations", Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", false)),
                Map.of("name", "backend_status", "description", "Read the selected provider and its available preparation snapshot without loading the model.",
                        "inputSchema", Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                        "annotations", Map.of("readOnlyHint", true)),
                Map.of("name", "infer_text", "description", "Run one explicit text inference using the configured provider; model outputs are untrusted candidate text.",
                        "inputSchema", Map.of("type", "object", "additionalProperties", false,
                                "properties", Map.of("prompt", Map.of("type", "string"),
                                        "maxTokens", Map.of("type", "integer", "minimum", 1, "maximum", 32768),
                                        "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 3600)),
                                "required", List.of("prompt")))));
        if (!sessionAgentTools.isEmpty()) {
            tools.add(Map.of("name", "run_agent", "description",
                    "Run a bounded Java/JNI coding-agent session against operator-configured repository tools; "
                            + "candidate previews do not authorize source application or prove behavior.",
                    "inputSchema", Map.of("type", "object", "additionalProperties", false,
                            "properties", Map.of("goal", Map.of("type", "string"),
                                    "maxSteps", Map.of("type", "integer", "minimum", 1,
                                            "maximum", agentLimits.maxSteps()),
                                    "maxTokens", Map.of("type", "integer", "minimum", 1,
                                            "maximum", agentLimits.maxTokens()),
                                    "timeoutSeconds", Map.of("type", "integer", "minimum", 1,
                                            "maximum", agentLimits.timeout().toSeconds())),
                            "required", List.of("goal")),
                    "annotations", Map.of("readOnlyHint", false, "destructiveHint", false,
                            "idempotentHint", false, "openWorldHint", false)));
        }
        if (agentTools != null) {
            tools.add(Map.of("name", "agent_run", "description",
                    "Run a bounded plan/tool/result loop over the explicitly configured workspace using the selected model provider.",
                    "inputSchema", Map.of("type", "object", "additionalProperties", false,
                            "properties", Map.of("goal", Map.of("type", "string"),
                                    "maxTokens", Map.of("type", "integer", "minimum", 1, "maximum", 32768),
                                    "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 3600),
                                    "maxSteps", Map.of("type", "integer", "minimum", 1, "maximum", 128),
                                    "maxModelCalls", Map.of("type", "integer", "minimum", 1, "maximum", 64)),
                            "required", List.of("goal")),
                    "annotations", Map.of("readOnlyHint", true)));
        }
        return List.copyOf(tools);
    }

    private Map<String, Object> backendStatus() {
        Map<String, Object> result = new LinkedHashMap<>(toolResult(backend.id(), false));
        if (backend instanceof LazyModelBackend lazy) {
            ModelArtifactCache.Progress progress = lazy.progress();
            result.put("structuredContent", Map.of("backendId", backend.id(),
                    "modelState", progress.state().name(), "processedBytes", progress.bytes()));
        }
        return result;
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

    private static boolean echoableId(Object id) {
        try {
            // These are the largest fixed protocol-error and tool-error envelopes.
            // Admit their escaped UTF-8 bytes, not raw ID characters or an arbitrary ID cap.
            encoded(error(id, -32603, "MCP response exceeds bounded size"));
            encoded(success(id, toolResult("Model inference interrupted", true)));
            return true;
        } catch (IOException oversized) {
            return false;
        }
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
