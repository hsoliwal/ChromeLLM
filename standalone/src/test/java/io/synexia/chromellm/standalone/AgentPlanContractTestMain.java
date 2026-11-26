// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeoutException;

/** No-model, no-network admission and MCP contract tests. */
public final class AgentPlanContractTestMain {
    private static int checks;

    private AgentPlanContractTestMain() { }

    public static void main(String[] args) throws Exception {
        String candidate = "{\"steps\":["
                + "{\"id\":\"finish\",\"tool\":\"infer_text\",\"dependsOn\":[\"start\"],\"arguments\":{\"prompt\":\"Hello\",\"maxTokens\":8}},"
                + "{\"id\":\"z\",\"tool\":\"backend_status\",\"dependsOn\":[],\"arguments\":{}},"
                + "{\"id\":\"start\",\"tool\":\"backend_status\",\"dependsOn\":[],\"arguments\":{}}]}";
        AgentPlan ordered = AgentPlan.compile(McpJson.parse(candidate));
        check(ordered.steps().stream().map(AgentPlan.Step::id).toList().equals(List.of("start", "finish", "z")),
                "lexical Kahn order");
        check(ordered.json().equals(AgentPlan.compile(McpJson.parse(ordered.json())).json()),
                "canonical JSON idempotence");
        check(ordered.structuredContent().containsKey("steps"), "structured content missing");
        check(AgentPlan.compile(McpJson.parse("{\"steps\":[{\"id\":\"a\",\"tool\":\"backend_status\",\"dependsOn\":[],\"arguments\":{}}]}"))
                .steps().size() == 1, "single step rejected");
        reject("{\"steps\":[]}");
        reject("{\"steps\":{},\"other\":1}");
        reject("{\"steps\":[{\"id\":\"a\",\"tool\":\"shell\",\"dependsOn\":[],\"arguments\":{}}]}");
        reject("{\"steps\":[{\"id\":\"a\",\"tool\":\"backend_status\",\"dependsOn\":[],\"arguments\":{\"path\":\"/tmp\"}}]}");
        reject("{\"steps\":[{\"id\":\"a\",\"tool\":\"infer_text\",\"dependsOn\":[],\"arguments\":{\"prompt\":\"a\",\"timeoutSeconds\":1.1}}]}");
        reject("{\"steps\":[{\"id\":\"a\",\"tool\":\"infer_text\",\"dependsOn\":[],\"arguments\":{\"prompt\":\"a\",\"maxTokens\":32769}}]}");
        reject("{\"steps\":[{\"id\":\"a\",\"tool\":\"backend_status\",\"dependsOn\":[\"ghost\"],\"arguments\":{}}]}");
        reject("{\"steps\":[{\"id\":\"a\",\"tool\":\"backend_status\",\"dependsOn\":[\"b\"],\"arguments\":{}},"
                + "{\"id\":\"b\",\"tool\":\"backend_status\",\"dependsOn\":[\"a\"],\"arguments\":{}}]}");
        reject("{\"steps\":[{\"id\":\"a\",\"tool\":\"backend_status\",\"dependsOn\":[\"a\"],\"arguments\":{}}]}");
        reject("{\"steps\":[{\"id\":\"a\",\"tool\":\"backend_status\",\"dependsOn\":[],\"arguments\":{}},"
                + "{\"id\":\"a\",\"tool\":\"backend_status\",\"dependsOn\":[],\"arguments\":{}}]}");
        reject("{\"steps\":[{\"id\":\"a\",\"tool\":\"backend_status\",\"dependsOn\":[\"z\",\"z\"],\"arguments\":{}},"
                + "{\"id\":\"z\",\"tool\":\"backend_status\",\"dependsOn\":[],\"arguments\":{}}]}");
        reject("{\"steps\":[{\"id\":\"a\",\"tool\":\"backend_status\",\"dependsOn\":[],\"arguments\":{},\"extra\":true}]}");
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 17; i++) many.add(step("a" + i));
        rejectObject(Map.of("steps", many));
        rejectObject(Map.of("steps", List.of(Map.of("id", "a", "tool", "infer_text",
                "dependsOn", List.of(), "arguments", Map.of("prompt", "x".repeat(8193))))));

        AtomicInteger invocations = new AtomicInteger();
        ModelBackend provider = fake(invocations, candidate, false, false);
        check(AgentPlanner.plan(provider, "analyze model", 256, Duration.ofSeconds(10)).steps().size() == 3,
                "provider planning failed");
        check(invocations.get() == 1, "multiple candidate-generation calls");
        rejectIo(() -> AgentPlanner.plan(provider, " ", 256, Duration.ofSeconds(10)));
        rejectIo(() -> AgentPlanner.plan(provider, "x".repeat(4097), 256, Duration.ofSeconds(10)));
        check(invocations.get() == 1, "invalid tasks called model");
        rejectIo(() -> AgentPlanner.plan(fake(new AtomicInteger(), candidate, true, false),
                "x", 256, Duration.ofSeconds(10)));
        rejectIo(() -> AgentPlanner.plan(fake(new AtomicInteger(), candidate, false, true),
                "x", 256, Duration.ofSeconds(10)));
        rejectIo(() -> AgentPlanner.plan(fake(new AtomicInteger(), "{\"steps\":[]}", false, false),
                "x", 256, Duration.ofSeconds(10)));
        planningHonorsDeadlineAndIdentity(candidate);

        String wire = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"plan_steps\",\"arguments\":{\"task\":\"inspect build\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"plan_steps\",\"arguments\":{\"task\":\"x\",\"unknown\":true}}}") + "\n";
        AtomicInteger wireCalls = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpStdioServer.serve(fake(wireCalls, candidate, false, false),
                new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)), output);
        String[] lines = output.toString(StandardCharsets.UTF_8).split("\n");
        check(lines.length == 4, "MCP replied to notification or omitted result");
        check(lines[1].contains("plan_steps"), "MCP tool not advertised");
        check(lines[2].contains("structuredContent") && lines[2].contains("finish")
                && lines[2].contains("isError\":false"), "MCP plan response invalid");
        check(lines[3].contains("-32602"), "MCP invalid request accepted");
        check(wireCalls.get() == 1, "MCP call count incorrect");
        System.out.println("Agent plan contract checks passed: " + checks);
    }

    private static Map<String, Object> step(String id) {
        return Map.of("id", id, "tool", "backend_status", "dependsOn", List.of(), "arguments", Map.of());
    }

    private static void planningHonorsDeadlineAndIdentity(String candidate) throws Exception {
        ControlledProvider preinterrupted = new ControlledProvider(candidate);
        Thread.currentThread().interrupt();
        try {
            rejectInterrupted(() -> AgentPlanner.plan(preinterrupted, "goal", 8, Duration.ofSeconds(2)));
            check(Thread.currentThread().isInterrupted(), "preexisting interrupt is preserved");
            check(preinterrupted.requests.isEmpty(), "preinterrupted planner never calls the provider");
        } finally { Thread.interrupted(); }

        ControlledProvider interruptedReply = new ControlledProvider(candidate);
        interruptedReply.interrupt = true;
        try {
            rejectInterrupted(() -> AgentPlanner.plan(interruptedReply, "goal", 8, Duration.ofSeconds(2)));
            check(Thread.currentThread().isInterrupted(), "provider interruption is preserved");
            check(interruptedReply.requests.size() == 1, "interrupted reply cannot trigger another inference");
        } finally { Thread.interrupted(); }

        ControlledProvider slow = new ControlledProvider(candidate);
        slow.delayMillis = 100;
        try {
            AgentPlanner.plan(slow, "goal", 8, Duration.ofMillis(40));
            throw new AssertionError("late planner response was accepted");
        } catch (TimeoutException expected) { checks++; }
        check(slow.requests.size() == 1, "deadline test actually exercised one delayed provider reply");

        for (boolean staleReceipt : List.of(false, true)) {
            ControlledProvider switching = new ControlledProvider(candidate);
            switching.changeIdentity = true;
            switching.staleIdentity = staleReceipt;
            rejectIo(() -> AgentPlanner.plan(switching, "goal", 8, Duration.ofSeconds(2)));
            check(switching.requests.size() == 1, "identity change refuses candidate without retry");
        }
        for (String identity : new String[] {null, " "}) {
            ControlledProvider invalid = new ControlledProvider(candidate);
            invalid.identity = identity;
            rejectIo(() -> AgentPlanner.plan(invalid, "goal", 8, Duration.ofSeconds(2)));
            check(invalid.requests.isEmpty(), "invalid provider identity is refused before inference");
        }

        ControlledProvider stable = new ControlledProvider(candidate);
        check(AgentPlanner.plan(stable, "goal", 8, Duration.ofSeconds(2)).steps().size() == 3,
                "deadline admission preserves successful planning");
        ModelRequest request = stable.requests.getFirst();
        check(request.timeout().equals(Duration.ofSeconds(2)),
                "single-call planner preserves the exact configured provider timeout");
        check(request.maxTokens() == 8, "provider token limit remains unchanged");
    }

    private static final class ControlledProvider implements ModelBackend {
        final String candidate;
        final List<ModelRequest> requests = new ArrayList<>();
        String identity = "controlled-provider";
        boolean interrupt, changeIdentity, staleIdentity;
        long delayMillis;
        ControlledProvider(String candidate) { this.candidate = candidate; }
        @Override public String id() { return identity; }
        @Override public ModelResponse infer(ModelRequest request) throws InterruptedException {
            requests.add(request);
            if (delayMillis != 0) Thread.sleep(delayMillis);
            if (interrupt) Thread.currentThread().interrupt();
            String previousIdentity = identity;
            if (changeIdentity) identity = "replacement-provider";
            try {
                String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(request.prompt().getBytes(StandardCharsets.UTF_8)));
                return new ModelResponse(candidate, staleIdentity ? previousIdentity : identity, digest);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
    }

    private static void rejectInterrupted(Action action) throws Exception {
        try { action.run(); throw new AssertionError("interrupted planner returned a candidate"); }
        catch (InterruptedException expected) { checks++; }
    }

    private static ModelBackend fake(AtomicInteger calls, String response, boolean badId, boolean badDigest) {
        return new ModelBackend() {
            @Override public String id() { return "synthetic-agent"; }
            @Override public ModelResponse infer(ModelRequest request) {
                calls.incrementAndGet();
                try {
                    String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(request.prompt().getBytes(StandardCharsets.UTF_8)));
                    return new ModelResponse(response, badId ? "wrong-provider" : id(),
                            badDigest ? "0".repeat(64) : digest);
                } catch (java.security.NoSuchAlgorithmException impossible) {
                    throw new AssertionError(impossible);
                }
            }
        };
    }

    private static void reject(String json) throws Exception {
        rejectIo(() -> AgentPlan.compile(McpJson.parse(json)));
    }
    private static void rejectObject(Object object) throws Exception { rejectIo(() -> AgentPlan.compile(object)); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void rejectIo(Action action) throws Exception {
        try { action.run(); throw new AssertionError("unsafe agent input accepted"); }
        catch (IOException expected) { checks++; }
    }
    private static void check(boolean truth, String reason) {
        checks++;
        if (!truth) throw new AssertionError(reason);
    }
}
