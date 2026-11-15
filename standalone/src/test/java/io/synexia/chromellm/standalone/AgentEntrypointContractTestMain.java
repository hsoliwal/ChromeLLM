// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Entry-point contracts: explicit operator configuration, passive discovery and bounded MCP calls. */
public final class AgentEntrypointContractTestMain {
    private static int checks;
    private AgentEntrypointContractTestMain() { }

    public static void main(String[] args) throws Exception {
        commandLineAdmission();
        goalPreflight();
        mcpDiscovery();
        mcpPlanningAndExecution();
        mcpExecutionAndBudgets();
        mcpFailureAndIsolation();
        fractionalRequestsDoNotTerminateSession();
        System.out.println("PASS agent CLI/MCP contracts: " + checks + " checks");
    }

    private static void goalPreflight() throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("agent-goal-contract-");
        java.nio.file.Path goal = directory.resolve("goal.txt");
        AgentSession.Limits small = new AgentSession.Limits(2, 64, 1024, 512, Duration.ofSeconds(2));
        try {
            try { StandaloneMain.readAgentGoal(directory, small); throw new AssertionError("directory accepted"); }
            catch (IOException expected) { checks++; }
            java.nio.file.Files.writeString(goal, "  \n");
            reject(() -> StandaloneMain.readAgentGoal(goal, small));
            java.nio.file.Files.writeString(goal, "नमस्ते /CaseSensitive");
            check(StandaloneMain.readAgentGoal(goal, small).equals("नमस्ते /CaseSensitive"));
            java.nio.file.Files.writeString(goal, "a".repeat(1025));
            try { StandaloneMain.readAgentGoal(goal, small); throw new AssertionError("oversized goal accepted"); }
            catch (IOException expected) { checks++; }
            java.nio.file.Files.write(goal, new byte[] {(byte) 0xc3, (byte) 0x28});
            try { StandaloneMain.readAgentGoal(goal, small); throw new AssertionError("invalid UTF8 accepted"); }
            catch (IOException expected) { checks++; }
        } finally {
            java.nio.file.Files.deleteIfExists(goal);
            java.nio.file.Files.deleteIfExists(directory);
        }
    }

    private static void commandLineAdmission() throws Exception {
        Map<String, String> valid = StandaloneMain.parse(new String[] {
                "--agent", "true", "--agent-endpoint", "http://127.0.0.1:32123",
                "--agent-token-env", "CHROMELLM_TEST_AGENT_TOKEN", "--agent-max-steps", "3"});
        StandaloneMain.validateAgentMode(valid);
        check(StandaloneMain.agentLimits(valid).maxSteps() == 3);
        StandaloneMain.validateAgentMode(Map.of("--mcp-stdio", "true"));
        StandaloneMain.validateAgentMode(Map.of());
        reject(() -> StandaloneMain.validateAgentMode(Map.of("--agent", "true")));
        reject(() -> StandaloneMain.validateAgentMode(Map.of("--agent", "true", "--stdio", "true")));
        reject(() -> StandaloneMain.validateAgentMode(Map.of("--agent", "true", "--mcp-stdio", "true")));
        reject(() -> StandaloneMain.validateAgentMode(Map.of("--agent", "true", "--list-devices", "true")));
        reject(() -> StandaloneMain.validateAgentMode(Map.of("--agent-endpoint", "http://127.0.0.1:1")));
        reject(() -> StandaloneMain.validateAgentMode(Map.of("--agent", "yes")));
        reject(() -> StandaloneMain.agentLimits(Map.of("--agent-max-steps", "0")));
        reject(() -> StandaloneMain.agentLimits(Map.of("--timeout-seconds", "0")));
        reject(() -> StandaloneMain.agentTools(valid, Map.of()));
        reject(() -> StandaloneMain.agentTools(valid, Map.of("CHROMELLM_TEST_AGENT_TOKEN", "bad")));
        reject(() -> StandaloneMain.agentTools(Map.of("--agent-token-env", "bad-name",
                "--agent-endpoint", "http://127.0.0.1:1"), Map.of()));
        try (CodingAgentTools tools = StandaloneMain.agentTools(valid,
                Map.of("CHROMELLM_TEST_AGENT_TOKEN", "a".repeat(64)))) {
            check(!tools.tools().isEmpty());
        }
        reject(() -> StandaloneMain.parse(new String[] {"--agent", "true", "--agent", "true"}));
    }

    private static void mcpDiscovery() throws Exception {
        ScriptBackend backend = new ScriptBackend();
        String input = initialize() + line(2, "tools/list", Map.of())
                + line(3, "tools/call", Map.of("name", "backend_status", "arguments", Map.of()));
        List<Map<?, ?>> plain = exchange(backend, List.of(), input);
        Map<?, ?> listed = (Map<?, ?>) plain.get(1).get("result");
        List<?> plainTools = (List<?>) listed.get("tools");
        check(plainTools.size() == 3);
        check(((Map<?, ?>) plainTools.get(0)).get("name").equals("plan_steps"));
        List<Map<?, ?>> configured = exchange(backend, List.of(statusTool(new AtomicInteger())), input);
        List<?> tools = (List<?>) ((Map<?, ?>) configured.get(1).get("result")).get("tools");
        check(tools.size() == 4);
        check(((Map<?, ?>) tools.get(0)).get("name").equals("plan_steps"));
        check(((Map<?, ?>) tools.get(3)).get("name").equals("run_agent"));
        check(backend.calls == 0); // Listing/status never loads a model or executes a tool.
        List<Map<?, ?>> absent = exchange(backend, List.of(), initialize() + agent(4, Map.of("goal", "inspect")));
        check(absent.get(1).containsKey("error"));
    }

    private static void mcpPlanningAndExecution() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ScriptBackend backend = new ScriptBackend(
                "{\"steps\":[{\"id\":\"inspect\",\"tool\":\"backend_status\","
                        + "\"dependsOn\":[],\"arguments\":{}}]}",
                "{\"type\":\"tool\",\"name\":\"status\",\"arguments\":{}}",
                "{\"type\":\"final\",\"answer\":\"inspection complete\"}");
        List<Map<?, ?>> replies = exchange(backend, List.of(statusTool(calls)), initialize()
                + line(2, "tools/call", Map.of("name", "plan_steps", "arguments",
                        Map.of("task", "private planning task")))
                + agent(3, Map.of("goal", "separate execution goal")));
        check(replies.size() == 3);
        Map<?, ?> planning = (Map<?, ?>) replies.get(1).get("result");
        check(Boolean.FALSE.equals(planning.get("isError")));
        Map<?, ?> plan = (Map<?, ?>) planning.get("structuredContent");
        List<?> steps = (List<?>) plan.get("steps");
        check(steps.size() == 1);
        check(((Map<?, ?>) steps.get(0)).get("tool").equals("backend_status"));
        String rendered = (String) ((Map<?, ?>) ((List<?>) planning.get("content")).get(0)).get("text");
        check(McpJson.parse(rendered).equals(plan));
        Map<?, ?> execution = (Map<?, ?>) replies.get(2).get("result");
        check(Boolean.FALSE.equals(execution.get("isError")));
        Map<?, ?> result = (Map<?, ?>) execution.get("structuredContent");
        check(result.get("status").equals("COMPLETED"));
        check(((java.math.BigDecimal) result.get("modelCalls")).intValueExact() == 2);
        check(((java.math.BigDecimal) result.get("toolCalls")).intValueExact() == 1);
        check(backend.calls == 3 && calls.get() == 1); // Planning proposes steps without executing them.
        check(!backend.lastPrompt.contains("private planning task"));
        check(backend.lastPrompt.contains("separate execution goal"));
    }

    private static void mcpExecutionAndBudgets() throws Exception {
        AtomicInteger tools = new AtomicInteger();
        ScriptBackend backend = new ScriptBackend(
                "{\"type\":\"tool\",\"name\":\"status\",\"arguments\":{}}",
                "{\"type\":\"final\",\"answer\":\"Exact नमस्ते /CaseSensitive\"}");
        List<Map<?, ?>> replies = exchange(backend, List.of(statusTool(tools)), initialize()
                + agent(2, Map.of("goal", "inspect")));
        Map<?, ?> result = (Map<?, ?>) replies.get(1).get("result");
        check(Boolean.FALSE.equals(result.get("isError")));
        Map<?, ?> structured = (Map<?, ?>) result.get("structuredContent");
        check(structured.get("answer").equals("Exact नमस्ते /CaseSensitive"));
        check(((java.math.BigDecimal) structured.get("modelCalls")).intValueExact() == 2);
        check(((java.math.BigDecimal) structured.get("toolCalls")).intValueExact() == 1);
        check(((List<?>) structured.get("trace")).size() == 2);
        check(tools.get() == 1 && backend.calls == 2);
        ScriptBackend unused = new ScriptBackend();
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                Map.of("goal", "inspect", "maxSteps", 5),
                Map.of("goal", "inspect", "maxTokens", 513),
                Map.of("goal", "inspect", "timeoutSeconds", 11),
                Map.of("goal", "inspect", "maxSteps", new java.math.BigDecimal("1e10000")),
                Map.of("goal", "inspect", "endpoint", "http://untrusted.invalid"),
                Map.of("goal", " "), Map.of("goal", 1), Map.of("goal", "x".repeat(65537)))) {
            List<Map<?, ?>> refused = exchange(unused, List.of(statusTool(tools)),
                    initialize() + agent(2, invalid));
            check(refused.get(1).containsKey("error"));
        }
        check(unused.calls == 0 && tools.get() == 1);
    }

    private static void mcpFailureAndIsolation() throws Exception {
        ScriptBackend bad = new ScriptBackend("{\"type\":\"tool\",\"name\":\"apply\",\"arguments\":{}}");
        List<Map<?, ?>> replies = exchange(bad, List.of(statusTool(new AtomicInteger())),
                initialize() + agent(2, Map.of("goal", "inspect")));
        Map<?, ?> failed = (Map<?, ?>) replies.get(1).get("result");
        check(Boolean.TRUE.equals(failed.get("isError")));
        check(failed.toString().contains("UNKNOWN_TOOL"));
        ScriptBackend fresh = new ScriptBackend(
                "{\"type\":\"final\",\"answer\":\"first\"}",
                "{\"type\":\"final\",\"answer\":\"second\"}");
        replies = exchange(fresh, List.of(statusTool(new AtomicInteger())), initialize()
                + agent(2, Map.of("goal", "first-private-goal"))
                + agent(3, Map.of("goal", "second-private-goal")));
        check(replies.size() == 3 && fresh.calls == 2);
        check(!fresh.lastPrompt.contains("first-private-goal"));
    }

    private static void fractionalRequestsDoNotTerminateSession() throws Exception {
        ScriptBackend backend = new ScriptBackend();
        for (String tool : List.of("infer_text", "plan_steps")) {
            for (String value : List.of("1.5", "1e10000", "2147483648")) {
                String textKey = tool.equals("infer_text") ? "prompt" : "task";
                String invalid = line(2, "tools/call", Map.of("name", tool, "arguments",
                        Map.of(textKey, "inspect", "maxTokens", new java.math.BigDecimal(value))));
                List<Map<?, ?>> replies = exchange(backend, List.of(), initialize() + invalid
                        + line(3, "ping", Map.of()));
                check(replies.size() == 3);
                check(((Map<?, ?>) replies.get(1).get("error")).get("code")
                        .equals(new java.math.BigDecimal(-32602)));
                check(replies.get(2).containsKey("result"));
            }
        }
        check(backend.calls == 0);
    }

    private static AgentSession.Tool statusTool(AtomicInteger calls) {
        return new AgentSession.Tool("status", "Read status",
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                (arguments, remaining) -> { calls.incrementAndGet(); return "ready"; });
    }

    private static String initialize() throws IOException {
        return line(1, "initialize", Map.of("protocolVersion", McpStdioServer.VERSION,
                "capabilities", Map.of(), "clientInfo", Map.of("name", "contract", "version", "1")))
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n";
    }

    private static String agent(int id, Map<String, Object> arguments) throws IOException {
        return line(id, "tools/call", Map.of("name", "run_agent", "arguments", arguments));
    }

    private static String line(int id, String method, Map<String, Object> params) throws IOException {
        return McpJson.encode(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params),
                StdioProtocol.MAX_PROMPT_BYTES) + "\n";
    }

    private static List<Map<?, ?>> exchange(ModelBackend backend, List<AgentSession.Tool> tools,
                                             String input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpStdioServer.serve(backend, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                output, tools, new AgentSession.Limits(4, 512, 65536, 16384, Duration.ofSeconds(10)));
        java.util.ArrayList<Map<?, ?>> results = new java.util.ArrayList<>();
        for (String line : output.toString(StandardCharsets.UTF_8).lines().toList()) {
            results.add((Map<?, ?>) McpJson.parse(line));
        }
        return results;
    }

    private static final class ScriptBackend implements ModelBackend {
        private final List<String> replies;
        private int calls;
        private String lastPrompt = "";
        ScriptBackend(String... replies) { this.replies = List.of(replies); }
        @Override public String id() { return "entrypoint-fixture"; }
        @Override public ModelResponse infer(ModelRequest request) throws IOException {
            lastPrompt = request.prompt();
            if (calls >= replies.size()) throw new AssertionError("unexpected model call");
            try {
                return new ModelResponse(replies.get(calls++), id(), HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(request.prompt().getBytes(StandardCharsets.UTF_8))));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void reject(Checked operation) throws Exception {
        try { operation.run(); throw new AssertionError("invalid configuration accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("agent entrypoint contract failed");
        checks++;
    }
}
