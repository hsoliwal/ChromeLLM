// SPDX-License-Identifier: Apache-2.0
// Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project.
package io.synexia.chromellm.standalone;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Real JSON-RPC framing with a deterministic provider; no hosted inference. */
public final class AgentTransportContractTestMain {
    private static int checks;
    private AgentTransportContractTestMain() { }

    public static void main(String[] args) throws Exception { run(); }

    static void run() throws Exception {
        // This is also the absent-before regression: the baseline rejects these flags.
        Map<String, String> options = StandaloneMain.parse(new String[]{
                "--agent", "true", "--workspace", ".", "--agent-max-steps", "4", "--agent-max-calls", "3"});
        check(options.get("--agent").equals("true"), "agent options must parse");
        rejectOptions(new String[]{"--workspace", "."}, "workspace");
        rejectOptions(new String[]{"--agent-max-steps", "3"}, "agent");
        rejectOptions(new String[]{"--agent", "true"}, "workspace");
        rejectOptions(new String[]{"--agent", "true", "--workspace", ".", "--stdio", "true"}, "exclusive");
        rejectOptions(new String[]{"--agent", "true", "--workspace", ".", "--mcp-stdio", "true"}, "exclusive");
        rejectOptions(new String[]{"--agent", "true", "--workspace", ".", "--list-devices", "true"}, "exclusive");
        rejectOptions(new String[]{"--agent", "maybe"}, "true or false");
        rejectOptions(new String[]{"--mcp-stdio", "true", "--agent-max-calls", "2"}, "agent");

        Path workspace = Files.createTempDirectory("chromellm-agent-wire-");
        try {
            Files.writeString(workspace.resolve("Example.java"), "class Example { /* नमस्ते agent-source-marker */ }\n");
            ScriptedBackend provider = new ScriptedBackend();
            List<Map<?, ?>> old = session(provider, null, List.of(request(2, "tools/list", Map.of()),
                    call(3, Map.of("goal", "inspect"))));
            check(list(result(old.get(1)).get("tools")).size() == 3, "baseline planning tool list changed");
            check(old.get(2).containsKey("error") && provider.calls == 0, "agent enabled without a workspace");

            List<Map<?, ?>> replies = session(provider, workspace, List.of(request(2, "tools/list", Map.of()),
                    call(3, Map.of("goal", "inspect the source", "maxTokens", 512))));
            check(list(result(replies.get(1)).get("tools")).size() == 4, "configured agent tool missing");
            Map<?, ?> execution = result(replies.get(2));
            check(Boolean.FALSE.equals(execution.get("isError")), "valid agent run failed");
            Map<?, ?> details = map(execution.get("structuredContent"));
            check("source inspected".equals(details.get("answer")), "agent final answer missing");
            check(details.get("modelCalls").toString().equals("2"), "model call count missing");
            check(details.get("toolCalls").toString().equals("1"), "tool call count missing");
            check(!list(details.get("events")).isEmpty(), "agent progress missing");
            check(provider.calls == 2 && provider.sawObservation, "tool result did not reach inference");

            ScriptedBackend planner = new ScriptedBackend();
            planner.failure = "plan";
            Map<?, ?> existingPlan = result(session(planner, workspace, List.of(request(4, "tools/call",
                    Map.of("name", "plan_steps", "arguments", Map.of("task", "inspect"))))).get(1));
            check(Boolean.FALSE.equals(existingPlan.get("isError")) && planner.calls == 1,
                    "existing candidate-only planning changed with an enabled workspace");
            check(list(map(existingPlan.get("structuredContent")).get("steps")).size() == 1,
                    "existing candidate plan was lost");

            for (String arguments : List.of("{}", "{\"goal\":\" \"}",
                    "{\"goal\":\"x\",\"workspace\":\"/\"}", "{\"goal\":\"x\",\"maxSteps\":0}",
                    "{\"goal\":\"x\",\"maxModelCalls\":0}", "{\"goal\":\"x\",\"maxTokens\":1.5}",
                    "{\"goal\":\"x\",\"maxTokens\":1e100}", "{\"goal\":\"x\",\"timeoutSeconds\":3601}")) {
                ScriptedBackend unused = new ScriptedBackend();
                Map<?, ?> reply = session(unused, workspace,
                        List.of(call(4, map(McpJson.parse(arguments))))).get(1);
                check(reply.containsKey("error") && unused.calls == 0, "invalid agent arguments reached model: " + arguments);
            }

            ScriptedBackend failing = new ScriptedBackend();
            failing.failure = "io";
            String failure = McpJson.encode(session(failing, workspace, List.of(call(2, Map.of("goal", "x")))).get(1), 10000);
            check(failure.contains("\"isError\":true") && !failure.contains("private-provider-diagnostic"), "provider error leaked");
            failing = new ScriptedBackend();
            failing.failure = "timeout";
            check(Boolean.TRUE.equals(result(session(failing, workspace,
                    List.of(call(2, Map.of("goal", "x")))).get(1)).get("isError")), "deadline failure escaped transport");
            failing = new ScriptedBackend();
            failing.failure = "number";
            List<Map<?, ?>> malformedNumber = session(failing, workspace, List.of(
                    call(2, Map.of("goal", "x")), request(3, "ping", Map.of())));
            check(Boolean.TRUE.equals(result(malformedNumber.get(1)).get("isError")),
                    "model numeric overflow escaped the checked tool result");
            check(malformedNumber.get(2).containsKey("result"), "MCP session stopped after invalid model number");
            failing = new ScriptedBackend();
            failing.failure = "interrupt";
            try {
                check(Boolean.TRUE.equals(result(session(failing, workspace,
                        List.of(call(2, Map.of("goal", "x")))).get(1)).get("isError")), "interruption escaped transport");
                check(Thread.currentThread().isInterrupted(), "transport cleared interruption");
            } finally { Thread.interrupted(); }
        } finally {
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("agent transport contract checks passed: " + checks);
    }

    private static List<Map<?, ?>> session(ModelBackend backend, Path workspace, List<Map<String, Object>> requests)
            throws Exception {
        StringBuilder input = new StringBuilder(McpJson.encode(request(1, "initialize", Map.of(
                "protocolVersion", McpStdioServer.VERSION, "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test", "version", "1"))), 10000)).append('\n');
        input.append("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
        for (Map<String, Object> request : requests) input.append(McpJson.encode(request, 65536)).append('\n');
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            // Reflection keeps the absent-before transport regression executable against the baseline.
            var serve = McpStdioServer.class.getDeclaredMethod("serve", ModelBackend.class,
                    InputStream.class, OutputStream.class, Path.class);
            serve.invoke(null, backend, new ByteArrayInputStream(input.toString().getBytes(StandardCharsets.UTF_8)), output, workspace);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception exception) throw exception;
            throw wrapped;
        }
        List<Map<?, ?>> result = new ArrayList<>();
        for (String line : output.toString(StandardCharsets.UTF_8).split("\n")) result.add(map(McpJson.parse(line)));
        check(result.size() == requests.size() + 1, "notification or progress corrupted JSON-RPC framing");
        return result;
    }

    private static Map<String, Object> request(int id, String method, Map<?, ?> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }
    private static Map<String, Object> call(int id, Map<?, ?> arguments) {
        return request(id, "tools/call", Map.of("name", "agent_run", "arguments", arguments));
    }
    private static Map<?, ?> result(Map<?, ?> message) { return map(message.get("result")); }
    private static Map<?, ?> map(Object value) { return (Map<?, ?>) value; }
    private static List<?> list(Object value) { return (List<?>) value; }

    private static void rejectOptions(String[] arguments, String expected) throws Exception {
        try { StandaloneMain.main(arguments); throw new AssertionError("invalid mode was accepted"); }
        catch (IllegalArgumentException failure) {
            check(failure.getMessage().contains(expected), "wrong early mode refusal: " + failure.getMessage());
        }
    }

    private static final class ScriptedBackend implements ModelBackend {
        int calls;
        boolean sawObservation;
        String failure;
        @Override public String id() { return "scripted-agent-provider"; }
        @Override public ModelResponse infer(ModelRequest request) throws IOException, InterruptedException, TimeoutException {
            calls++;
            if ("io".equals(failure)) throw new IOException("private-provider-diagnostic");
            if ("timeout".equals(failure)) throw new TimeoutException("private-provider-diagnostic");
            if ("interrupt".equals(failure)) throw new InterruptedException("private-provider-diagnostic");
            sawObservation |= request.prompt().contains("agent-source-marker");
            String answer = "plan".equals(failure)
                    ? "{\"steps\":[{\"id\":\"inspect\",\"tool\":\"backend_status\",\"dependsOn\":[],\"arguments\":{}}]}"
                    : "number".equals(failure)
                    ? "{\"type\":\"plan\",\"steps\":[{\"tool\":\"workspace.read\",\"arguments\":{\"path\":\"Example.java\",\"maxLines\":100e2147483647}}]}"
                    : calls == 1
                    ? "{\"type\":\"plan\",\"steps\":[{\"tool\":\"workspace.read\",\"arguments\":{\"path\":\"Example.java\"}}]}"
                    : "{\"type\":\"final\",\"text\":\"source inspected\"}";
            try {
                return new ModelResponse(answer, id(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(request.prompt().getBytes(StandardCharsets.UTF_8))));
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
