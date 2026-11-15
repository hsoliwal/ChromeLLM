/*
 * Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Adversarial executable contracts using real engine code and deterministic in-process ports. */
public final class AgentEngineContractTestMain {
    private static int checks;
    private AgentEngineContractTestMain() { }

    public static void main(String[] args) throws Exception {
        finalOnlyAndProgress();
        serialToolsAndContext();
        invalidPlansNeverExecute();
        duplicateCallsUseCanonicalArguments();
        numericNormalizationRejectsOverflow();
        receiptVerificationStopsTools();
        resourceLimits();
        deadlineAndInterruption();
        registryAndImmutableArguments();
        nullResultsAndCallbackFailures();
        System.out.println("AgentEngineContractTestMain passed: " + checks + " checks");
    }

    private static void finalOnlyAndProgress() throws Exception {
        Script backend = new Script("{\"type\":\"final\",\"text\":\"answer\"}");
        List<AgentEngine.Event> progress = new ArrayList<>();
        AgentEngine.Result result = new AgentEngine(backend, List.of(), progress::add)
                .run("private goal", 123, defaults());
        equal("answer", result.answer(), "final answer");
        equal(1, result.modelCalls(), "final model count");
        equal(0, result.toolCalls(), "final tool count");
        equal(123, backend.requests.getFirst().maxTokens(), "tokens forwarded");
        equal(progress, result.events(), "same ordered progress");
        int sequence = 0;
        for (AgentEngine.Event event : progress) {
            equal(++sequence, event.sequence(), "consecutive sequence");
            equal(java.util.Set.of("sequence", "phase", "tool"), event.asMap().keySet(), "metadata keys only");
        }
        check(!McpJson.encode(result.events().stream().map(AgentEngine.Event::asMap).toList(), 65536)
                .contains("private goal"), "progress excludes goal");
        equal(java.util.Set.of("answer", "modelCalls", "toolCalls", "events"), result.asMap().keySet(), "result keys");
        check(!backend.closed, "caller retains backend ownership");
        expect(UnsupportedOperationException.class, () -> result.events().clear());
    }

    private static void serialToolsAndContext() throws Exception {
        List<String> order = new ArrayList<>();
        Tool a = new Tool("a", order), b = new Tool("b", order);
        Script backend = new Script(plan(step("a", "{\"x\":1}"), step("b", "{\"x\":2}")),
                "{\"type\":\"final\",\"text\":\"done\"}");
        AgentEngine.Result result = new AgentEngine(backend, List.of(a, b), event -> { })
                .run("use capabilities", 32, defaults());
        equal(List.of("validate:a", "validate:b", "execute:a", "execute:b"), order, "whole plan validates before serial execution");
        equal(2, result.modelCalls(), "plan plus final calls");
        equal(2, result.toolCalls(), "two tool calls");
        check(backend.requests.get(1).prompt().contains("result-a"), "first observation fed back");
        check(backend.requests.get(1).prompt().contains("result-b"), "second observation fed back");
        check(backend.requests.get(1).prompt().contains("use capabilities"), "goal retained");
        check(backend.requests.getFirst().prompt().contains("inputSchema"), "schemas advertised");
        check(backend.requests.get(1).timeout().compareTo(backend.requests.getFirst().timeout()) < 0,
                "later inference receives remaining overall deadline");
    }

    private static void invalidPlansNeverExecute() throws Exception {
        List<String> invalid = List.of(
                "[]", "{}", "{\"type\":\"unknown\"}",
                "{\"type\":\"final\",\"text\":\"x\",\"extra\":1}",
                "{\"type\":\"final\",\"text\":7}", "{\"type\":\"final\",\"text\":\"  \"}",
                "{\"type\":\"plan\",\"steps\":[]}",
                "{\"type\":\"plan\",\"steps\":[],\"extra\":1}",
                plan(step("a", "{}"), step("missing", "{}")),
                plan(step("a", "{}"), "{\"tool\":\"a\",\"arguments\":null}"),
                plan(step("a", "{}"), "{\"tool\":\"a\",\"arguments\":[],\"extra\":1}"),
                plan(step("a", "{}"), "{\"tool\":\"a\",\"arguments\":{},\"extra\":1}"),
                plan(step("a", "{}"), step("a", "{\"invalid\":true}")),
                "{\"type\":\"final\",\"type\":\"final\",\"text\":\"x\"}",
                "{\"type\":\"final\",\"text\":\"x\"} {}",
                "```json\n{\"type\":\"final\",\"text\":\"x\"}\n```",
                plan(step("a", "{\"x\":1,\"x\":2}")));
        for (String candidate : invalid) {
            Tool tool = new Tool("a", new ArrayList<>());
            Script backend = new Script(candidate);
            expect(IOException.class, () -> new AgentEngine(backend, List.of(tool), event -> { })
                    .run("goal", 1, defaults()));
            equal(0, tool.executed, "invalid whole plan executes no tools");
            equal(1, backend.requests.size(), "invalid output stops model calls");
        }
    }

    private static void duplicateCallsUseCanonicalArguments() throws Exception {
        Tool tool = new Tool("a", new ArrayList<>());
        String first = "{\"nested\":{\"a\":1,\"b\":[2,3]},\"z\":4}";
        String second = "{\"z\":4.0,\"nested\":{\"b\":[2.0,3],\"a\":1}}";
        Script samePlan = new Script(plan(step("a", first), step("a", second)));
        expect(IOException.class, () -> new AgentEngine(samePlan, List.of(tool), e -> { })
                .run("goal", 1, defaults()));
        equal(0, tool.executed, "duplicate within plan is refused before execution");
        Script separatePlans = new Script(plan(step("a", first)), plan(step("a", second)));
        expect(IOException.class, () -> new AgentEngine(separatePlans, List.of(tool), e -> { })
                .run("goal", 1, defaults()));
        equal(1, tool.executed, "duplicate across turns cannot replay tool");
    }

    private static void numericNormalizationRejectsOverflow() throws Exception {
        Tool tool = new Tool("a", new ArrayList<>());
        Script overflowing = new Script(plan(step("a", "{\"x\":1}"), step("a", "{\"x\":100e2147483647}")));
        expect(IOException.class, () -> new AgentEngine(overflowing, List.of(tool), e -> { })
                .run("goal", 1, defaults()));
        equal(0, tool.executed, "overflowing normalization cannot execute tools");
        equal(1, overflowing.requests.size(), "overflowing normalization stops this run");
        Script equivalent = new Script(plan(step("a", "{\"x\":100e2}"), step("a", "{\"x\":1e4}")));
        expect(IOException.class, () -> new AgentEngine(equivalent, List.of(tool), e -> { })
                .run("goal", 1, defaults()));
        equal(0, tool.executed, "ordinary exponent-equivalent duplicates cannot execute tools");
        Script validExponent = new Script(plan(step("a", "{\"x\":100e2}")),
                "{\"type\":\"final\",\"text\":\"ok\"}");
        AgentEngine.Result result = new AgentEngine(validExponent, List.of(tool), e -> { })
                .run("goal", 1, defaults());
        equal("ok", result.answer(), "ordinary exponent arguments remain valid");
        equal(1, tool.executed, "ordinary exponent call executes once");
    }

    private static void receiptVerificationStopsTools() throws Exception {
        for (boolean provider : List.of(false, true)) {
            Tool tool = new Tool("a", new ArrayList<>());
            Script backend = new Script(plan(step("a", "{}")));
            backend.badProvider = provider;
            backend.badReceipt = !provider;
            expect(IOException.class, () -> new AgentEngine(backend, List.of(tool), e -> { })
                    .run("goal", 1, defaults()));
            equal(0, tool.executed, "unverified response never dispatches tools");
        }
    }

    private static void resourceLimits() throws Exception {
        Tool tool = new Tool("a", new ArrayList<>());
        Script steps = new Script(plan(step("a", "{\"x\":1}"), step("a", "{\"x\":2}")));
        expect(IOException.class, () -> new AgentEngine(steps, List.of(tool), e -> { })
                .run("goal", 1, limits(1, 8, 65536, 32768, Duration.ofSeconds(2))));
        equal(0, tool.executed, "step budget checked before execution");
        Script cumulativeSteps = new Script(plan(step("a", "{\"x\":1}")), plan(step("a", "{\"x\":2}")));
        expect(IOException.class, () -> new AgentEngine(cumulativeSteps, List.of(tool), e -> { })
                .run("goal", 1, limits(1, 8, 65536, 32768, Duration.ofSeconds(2))));
        equal(1, tool.executed, "step budget counts across plans");
        tool.executed = 0;
        Script calls = new Script(plan(step("a", "{}")));
        expect(IOException.class, () -> new AgentEngine(calls, List.of(tool), e -> { })
                .run("goal", 1, limits(16, 1, 65536, 32768, Duration.ofSeconds(2))));
        equal(1, calls.requests.size(), "model call budget is hard");
        Script initialContext = new Script("{\"type\":\"final\",\"text\":\"ok\"}");
        expect(IOException.class, () -> new AgentEngine(initialContext, List.of(), e -> { })
                .run("goal", 1, limits(16, 8, 1, 32768, Duration.ofSeconds(2))));
        equal(0, initialContext.requests.size(), "oversized initial prompt avoids inference");
        Tool huge = new Tool("a", new ArrayList<>()); huge.output = "é".repeat(100);
        Script oversizedTool = new Script(plan(step("a", "{}")));
        expect(IOException.class, () -> new AgentEngine(oversizedTool, List.of(huge), e -> { })
                .run("goal", 1, limits(16, 8, 65536, 150, Duration.ofSeconds(2))));
        equal(1, oversizedTool.requests.size(), "UTF-8 output cap stops later model call");
        Script oversizedModel = new Script("{\"type\":\"final\",\"text\":\"" + "é".repeat(33000) + "\"}");
        expect(IOException.class, () -> new AgentEngine(oversizedModel, List.of(), e -> { })
                .run("goal", 1, defaults()));
        Tool growing = new Tool("a", new ArrayList<>()); growing.output = "x".repeat(2000);
        Script growth = new Script(plan(step("a", "{}")));
        expect(IOException.class, () -> new AgentEngine(growth, List.of(growing), e -> { })
                .run("goal", 1, limits(16, 8, 1600, 32768, Duration.ofSeconds(2))));
        equal(1, growing.executed, "context growth checks tool observation");
        equal(1, growth.requests.size(), "context exhaustion never drops prior context");
        for (Runnable invalid : List.<Runnable>of(
                () -> limits(0, 1, 1, 1, Duration.ofSeconds(1)),
                () -> limits(1, 0, 1, 1, Duration.ofSeconds(1)),
                () -> limits(1, 1, 0, 1, Duration.ofSeconds(1)),
                () -> limits(1, 1, 1, 0, Duration.ofSeconds(1)),
                () -> limits(129, 1, 1, 1, Duration.ofSeconds(1)),
                () -> limits(1, 65, 1, 1, Duration.ofSeconds(1)),
                () -> limits(1, 1, 262145, 1, Duration.ofSeconds(1)),
                () -> limits(1, 1, 1, 262145, Duration.ofSeconds(1)),
                () -> limits(1, 1, 1, 1, Duration.ZERO),
                () -> limits(1, 1, 1, 1, Duration.ofHours(2)))) {
            expect(IllegalArgumentException.class, invalid::run);
        }
        equal(16, defaults().maxSteps(), "default steps");
        equal(8, defaults().maxModelCalls(), "default model turns");
        equal(65536, defaults().maxContextChars(), "default context");
        equal(32768, defaults().maxToolOutputBytes(), "default output");
    }

    private static void deadlineAndInterruption() throws Exception {
        Script interrupted = new Script("{\"type\":\"final\",\"text\":\"ok\"}");
        Thread.currentThread().interrupt();
        try {
            expect(InterruptedException.class, () -> new AgentEngine(interrupted, List.of(), e -> { })
                    .run("goal", 1, defaults()));
            check(Thread.currentThread().isInterrupted(), "interruption preserved");
            equal(0, interrupted.requests.size(), "interrupted run never infers");
        } finally { Thread.interrupted(); }
        AgentDeadline expired = AgentDeadline.start(Duration.ofNanos(1));
        expect(TimeoutException.class, expired::check);
        AgentDeadline deadline = AgentDeadline.start(Duration.ofSeconds(1));
        check(deadline.remaining().isPositive(), "remaining is positive");
        expect(IllegalArgumentException.class, () -> AgentDeadline.start(Duration.ofHours(2)));
        Tool tool = new Tool("a", new ArrayList<>());
        Script slow = new Script(plan(step("a", "{}"))); slow.delayMillis = 200;
        expect(TimeoutException.class, () -> new AgentEngine(slow, List.of(tool), e -> { })
                .run("goal", 1, limits(16, 8, 65536, 32768, Duration.ofMillis(100))));
        equal(0, tool.executed, "late model cannot start tool");
        equal(1, slow.requests.size(), "late inference was actually exercised");
        Tool slowTool = new Tool("a", new ArrayList<>()); slowTool.delayMillis = 300;
        Script afterTool = new Script(plan(step("a", "{\"x\":1}"), step("a", "{\"x\":2}")));
        expect(TimeoutException.class, () -> new AgentEngine(afterTool, List.of(slowTool), e -> { })
                .run("goal", 1, limits(16, 8, 65536, 32768, Duration.ofMillis(200))));
        equal(1, slowTool.executed, "shared deadline stops later tool");
        Tool interrupting = new Tool("a", new ArrayList<>()); interrupting.interrupt = true;
        Script toolInterrupt = new Script(plan(step("a", "{\"x\":1}"), step("a", "{\"x\":2}")));
        try {
            expect(InterruptedException.class, () -> new AgentEngine(toolInterrupt, List.of(interrupting), e -> { })
                    .run("goal", 1, defaults()));
            check(Thread.currentThread().isInterrupted(), "tool interrupt preserved");
            equal(1, interrupting.executed, "tool interrupt stops remaining plan");
        } finally { Thread.interrupted(); }
    }

    private static void registryAndImmutableArguments() throws Exception {
        Tool a = new Tool("a", new ArrayList<>());
        expect(IllegalArgumentException.class, () -> new AgentEngine(new Script(), List.of(a, a), e -> { }));
        a.mutate = true;
        Script backend = new Script(plan(step("a", "{\"nested\":{\"x\":1}}")),
                "{\"type\":\"final\",\"text\":\"ok\"}");
        new AgentEngine(backend, List.of(a), e -> { }).run("goal", 1, defaults());
        check(a.frozen, "tool argument snapshot is deeply immutable");
        Script failing = new Script(plan(step("a", "{}"))); a.fail = true;
        expect(IOException.class, () -> new AgentEngine(failing, List.of(a), e -> { })
                .run("goal", 1, defaults()));
        equal(1, failing.requests.size(), "tool error stops model loop");
        check(!failing.closed, "failure retains caller backend ownership");
    }

    private static void nullResultsAndCallbackFailures() throws Exception {
        Tool nullable = new Tool("a", new ArrayList<>()); nullable.output = null;
        Script backend = new Script(plan(step("a", "{}")), "{\"type\":\"final\",\"text\":\"ok\"}");
        AgentEngine.Result result = new AgentEngine(backend, List.of(nullable), e -> { })
                .run("goal", 1, defaults());
        equal("ok", result.answer(), "null JSON tool output is legitimate data");
        check(backend.requests.get(1).prompt().contains("\"result\":null"), "null observation retained");
        equal(List.of("start", "model_start", "model_complete", "tool_start", "tool_complete",
                "model_start", "model_complete", "complete"), result.events().stream().map(AgentEngine.Event::phase).toList(),
                "event phases preserve execution order");
        for (AgentEngine.Event event : result.events()) {
            equal(event.phase().startsWith("tool_") ? "a" : "", event.tool(), "only tool phases identify registered tool");
        }
        Script callbackBackend = new Script("{\"type\":\"final\",\"text\":\"ok\"}");
        expect(IOException.class, () -> new AgentEngine(callbackBackend, List.of(), e -> {
            throw new IllegalStateException("consumer failed");
        }).run("goal", 1, defaults()));
        equal(0, callbackBackend.requests.size(), "callback failure stops work");
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofHours(2))) {
            expect(IllegalArgumentException.class, () -> AgentDeadline.start(invalid));
        }
        expect(IllegalArgumentException.class, () -> new AgentEngine(new Script(), List.of(), e -> { }).run(" ", 1, defaults()));
        expect(IllegalArgumentException.class, () -> new AgentEngine(new Script(), List.of(), e -> { }).run("goal", 0, defaults()));
        expect(IllegalArgumentException.class, () -> new AgentEngine(new Script(), List.of(), e -> { }).run("goal", 32769, defaults()));
    }

    private static AgentEngine.Limits defaults() { return AgentEngine.Limits.defaults(Duration.ofSeconds(2)); }
    private static AgentEngine.Limits limits(int steps, int calls, int context, int output, Duration timeout) {
        return new AgentEngine.Limits(steps, calls, context, output, timeout);
    }
    private static String plan(String... steps) { return "{\"type\":\"plan\",\"steps\":[" + String.join(",", steps) + "]}"; }
    private static String step(String name, String arguments) { return "{\"tool\":\"" + name + "\",\"arguments\":" + arguments + "}"; }
    private static String sha(String input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }
    private static void check(boolean truth, String message) { checks++; if (!truth) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }
    private static void expect(Class<? extends Throwable> expected, Action action) throws Exception {
        checks++;
        try { action.run(); }
        catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError("Expected " + expected.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }

    private static final class Script implements ModelBackend {
        final List<String> outputs;
        final List<ModelRequest> requests = new ArrayList<>();
        boolean badProvider, badReceipt, closed;
        long delayMillis;
        Script(String... outputs) { this.outputs = List.of(outputs); }
        public String id() { return "scripted"; }
        public ModelResponse infer(ModelRequest request) throws IOException, InterruptedException {
            requests.add(request);
            if (delayMillis != 0) Thread.sleep(delayMillis);
            if (requests.size() > outputs.size()) throw new AssertionError("Unexpected model call");
            try {
                return new ModelResponse(outputs.get(requests.size() - 1), badProvider ? "wrong" : id(),
                        badReceipt ? "0".repeat(64) : sha(request.prompt()));
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            catch (Exception failure) { throw new IOException(failure); }
        }
        public void close() { closed = true; }
    }

    private static final class Tool implements AgentTool {
        final String name;
        final List<String> order;
        int executed;
        Object output;
        long delayMillis;
        boolean interrupt, fail, mutate, frozen;
        Tool(String name, List<String> order) { this.name = name; this.order = order; output = Map.of("text", "result-" + name); }
        public String name() { return name; }
        public String description() { return "Read a bounded test fixture"; }
        public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        public void validate(Map<String, Object> args) throws IOException {
            order.add("validate:" + name);
            if (args.containsKey("invalid")) throw new IOException("invalid argument");
            if (mutate && args.containsKey("nested")) {
                try { ((Map<?, ?>) args.get("nested")).clear(); }
                catch (UnsupportedOperationException expected) { frozen = true; }
            }
        }
        public Object execute(Map<String, Object> args, AgentDeadline deadline) throws IOException, InterruptedException {
            order.add("execute:" + name); executed++;
            if (fail) throw new IOException("tool failed");
            if (delayMillis != 0) Thread.sleep(delayMillis);
            if (interrupt) Thread.currentThread().interrupt();
            return output;
        }
    }
}
