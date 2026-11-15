// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** No-network JSON-RPC and tool execution contracts for MCP stdio. */
public final class McpStdioServerContractTestMain {
    private static int checks;

    private McpStdioServerContractTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            switch (args[0]) {
                case "--numeric-recovery" -> argumentRecovery();
                case "--response-budget" -> responseBudget();
                case "--provider-failure" -> providerFailure();
                case "--planning-recovery" -> planningRecovery();
                case "--agent-recovery" -> agentRecovery();
                case "--bounded-encoding-child" -> boundedEncodingChild();
                default -> throw new IllegalArgumentException("unknown contract case");
            }
            System.out.println("MCP selected contract checks passed: " + checks);
            return;
        }
        AtomicInteger inferCalls = new AtomicInteger();
        ModelBackend provider = new ModelBackend() {
            @Override public String id() { return "synthetic-jni"; }
            @Override public ModelResponse infer(ModelRequest request) throws IOException {
                inferCalls.incrementAndGet();
                if (request.prompt().equals("explode")) throw new IOException("sensitive diagnostics");
                check(request.maxTokens() > 0 && !request.timeout().isNegative(), "bad request defaults");
                return new ModelResponse("reply: " + request.prompt(), id(), "0".repeat(64));
            }
        };
        String in = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"backend_status\",\"arguments\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"infer_text\",\"arguments\":{\"prompt\":\"नमस्ते\",\"maxTokens\":8,\"timeoutSeconds\":3}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"infer_text\",\"arguments\":{\"prompt\":\"explode\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"infer_text\",\"arguments\":{\"prompt\":\"x\",\"maxTokens\":32769}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"hello\",\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"bogus\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":8,\"id\":9,\"method\":\"ping\"}") + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpStdioServer.serve(provider, new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)), out);
        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        check(lines.length == 9, "notifications wrote a reply or response missing");
        Map<?, ?> init = asMap(McpJson.parse(lines[0]));
        check(asMap(init.get("result")).get("protocolVersion").equals("2025-06-18"), "version negotiation");
        check(init.get("id").toString().equals("1"), "numeric id lost");
        check(lines[1].contains("infer_text") && lines[1].contains("backend_status"), "tool registry missing");
        check(lines[2].contains("synthetic-jni") && inferCalls.get() == 2, "backend status selection");
        check(lines[3].contains("reply: नमस्ते"), "Unicode tool response");
        check(lines[4].contains("isError\":true") && !lines[4].contains("sensitive"), "tool error leaked details");
        check(lines[5].contains("-32602"), "invalid params accepted");
        check(lines[6].contains("\"hello\""), "string id lost");
        check(lines[7].contains("-32601"), "unknown method accepted");
        check(lines[8].contains("-32700"), "duplicate JSON property accepted");
        check(inferCalls.get() == 2, "invalid calls invoked backend");
        check(McpJson.parse("{\"x\":1e1}") instanceof Map, "JSON numeric parse failed");
        rejectJson("{\"x\":\"\\ud800\"}");
        rejectJson("{\"x\":\"" + "\\" + "u٠٠٠٠\"}");
        rejectJson("[1,2,]");
        rejectJson("{\"x\":1,\"x\":2}");
        rejectIo(() -> McpStdioServer.serve(provider,
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream()));
        rejectIo(() -> McpStdioServer.serve(provider,
                new ByteArrayInputStream(new byte[]{(byte) 0xc3, (byte) 0x28, 10}),
                new ByteArrayOutputStream()));
        argumentRecovery();
        argumentOrderAndCase();
        invalidEnvelopes();
        providerFailure();
        planningRecovery();
        agentRecovery();
        responseBudget();
        encodingBounds();
        boundedEncodingMemory();
        System.out.println("MCP stdio contract checks passed: " + checks);
    }

    private static void argumentRecovery() throws Exception {
        List<String> invalid = new ArrayList<>(List.of(
                "{\"prompt\":\"X\",\"maxTokens\":1.5}",
                "{\"prompt\":\"X\",\"timeoutSeconds\":1.5}",
                "{}", "{\"prompt\":null}", "{\"prompt\":3}",
                "{\"prompt\":\" \\t\\r\\n\"}", "{\"prompt\":\"X\",\"other\":1}",
                "{\"prompt\":\"X\",\"MaxTokens\":8}"));
        for (String field : List.of("maxTokens", "timeoutSeconds")) {
            for (String value : List.of("0", "-0", "-1", "2147483648", "-2147483649",
                    "1e2147483647", "1e2147483648", "1e-2147483647", "1e1000000", "1e-1000000",
                    "0.1", "\"8\"", "true", "null")) {
                invalid.add("{\"prompt\":\"X\",\"" + field + "\":" + value + "}");
            }
        }
        invalid.add("{\"prompt\":\"X\",\"maxTokens\":32769}");
        invalid.add("{\"prompt\":\"X\",\"timeoutSeconds\":3601}");
        RecordingBackend provider = new RecordingBackend();
        List<String> requests = new ArrayList<>();
        for (int i = 0; i < invalid.size(); i++) {
            requests.add(infer("bad-" + i, invalid.get(i)));
            requests.add(infer("good-" + i, "{\"prompt\":\"MiXeD after " + i + "\"}"));
        }
        List<Map<?, ?>> replies = exchange(provider, requests);
        check(replies.size() == requests.size(), "invalid parameters ended or desynchronized the stream");
        for (int i = 0; i < invalid.size(); i++) {
            protocolError(replies.get(i * 2), "bad-" + i, -32602);
            toolReply(replies.get(i * 2 + 1), "good-" + i, false);
            ModelRequest request = provider.requests.get(i);
            check(request.prompt().equals("MiXeD after " + i), "prompt changed after invalid arguments");
            check(request.maxTokens() == 512 && request.timeout().equals(Duration.ofSeconds(120)),
                    "optional inference defaults changed");
        }
        check(provider.requests.size() == invalid.size(), "invalid arguments reached the model port");
    }

    private static void argumentOrderAndCase() throws Exception {
        RecordingBackend provider = new RecordingBackend();
        String prompt = "  MiXeD https://Example.test/Case/Sensitive?Key=VaLue \nनमस्ते 🦙  ";
        String[] fields = {"\"prompt\":" + json(prompt), "\"maxTokens\":8.00", "\"timeoutSeconds\":3e0"};
        List<String> requests = new ArrayList<>();
        for (int first = 0; first < 3; first++) {
            for (int second = 0; second < 3; second++) {
                if (first == second) continue;
                int third = 3 - first - second;
                requests.add(infer("order-" + requests.size(),
                        "{" + fields[first] + "," + fields[second] + "," + fields[third] + "}"));
            }
        }
        requests.add(infer("integral-exponents",
                "{\"timeoutSeconds\":3.6e3,\"maxTokens\":3.2768e4,\"prompt\":\"Limits\"}"));
        List<Map<?, ?>> replies = exchange(provider, requests);
        check(replies.size() == 7 && provider.requests.size() == 7, "argument permutation was dropped");
        for (int i = 0; i < 6; i++) {
            toolReply(replies.get(i), "order-" + i, false);
            ModelRequest request = provider.requests.get(i);
            check(request.prompt().equals(prompt), "case, whitespace or Unicode payload was normalized");
            check(request.maxTokens() == 8 && request.timeout().equals(Duration.ofSeconds(3)),
                    "argument order changed named argument binding");
        }
        toolReply(replies.get(6), "integral-exponents", false);
        check(provider.requests.get(6).maxTokens() == 32768
                && provider.requests.get(6).timeout().equals(Duration.ofSeconds(3600)),
                "mathematically integral exponent notation was rejected");
    }

    private static void invalidEnvelopes() throws Exception {
        RecordingBackend provider = new RecordingBackend();
        List<String> requests = new ArrayList<>();
        for (String id : List.of("null", "true", "[]", "{}", "1.5", "9223372036854775808",
                "-9223372036854775809", "1e2147483647")) {
            requests.add("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"infer_text\",\"arguments\":{\"prompt\":\"unused\"}}}");
        }
        requests.add("{\"jsonrpc\":\"2.0\",\"id\":\"array\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"infer_text\",\"arguments\":[]}}");
        requests.add("{\"jsonrpc\":\"2.0\",\"id\":\"status\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"backend_status\",\"arguments\":{\"prompt\":\"unused\"}}}");
        requests.add("{\"jsonrpc\":\"2.0\",\"id\":\"unknown\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"unknown\"}}");
        requests.add(infer("duplicate", "{\"prompt\":\"X\",\"maxTokens\":1,\"maxTokens\":2}"));
        requests.add(infer("unrepresentable", "{\"prompt\":\"X\",\"maxTokens\":1e2147483649}"));
        // A notification must never run inference, including a valid-looking tool call.
        requests.add("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"infer_text\",\"arguments\":{\"prompt\":\"unused\"}}}");
        requests.add("{\"jsonrpc\":\"2.0\",\"id\":-9223372036854775808,\"method\":\"ping\"}");
        requests.add("{\"jsonrpc\":\"2.0\",\"id\":9223372036854775807,\"method\":\"ping\"}");
        requests.add("{\"jsonrpc\":\"2.0\",\"id\":\"\",\"method\":\"ping\"}");
        requests.add(infer("after-envelopes", "{\"prompt\":\"Still Here\"}"));
        List<Map<?, ?>> replies = exchange(provider, requests);
        check(replies.size() == requests.size() - 1, "notification produced a response");
        for (int i = 0; i < 8; i++) protocolError(replies.get(i), null, -32600);
        protocolError(replies.get(8), "array", -32602);
        protocolError(replies.get(9), "status", -32602);
        protocolError(replies.get(10), "unknown", -32602);
        protocolError(replies.get(11), null, -32700);
        protocolError(replies.get(12), null, -32700);
        check(replies.get(13).get("id").toString().equals("-9223372036854775808"), "minimum ID changed");
        check(replies.get(14).get("id").toString().equals("9223372036854775807"), "maximum ID changed");
        check(replies.get(15).get("id").equals(""), "empty string ID rejected");
        toolReply(replies.get(16), "after-envelopes", false);
        check(provider.requests.size() == 1, "invalid envelope or notification ran inference");
    }

    private static void providerFailure() throws Exception {
        RecordingBackend provider = new RecordingBackend();
        List<String> failures = List.of("io-failure", "timeout-failure", "runtime-failure",
                "null-response", "wrong-identity", "invalid-unicode", "oversized-response");
        List<String> requests = new ArrayList<>();
        for (String failure : failures) {
            requests.add(infer(failure, "{\"prompt\":" + json(failure) + "}"));
            requests.add(infer("after-" + failure, "{\"prompt\":\"Normal\"}"));
        }
        List<Map<?, ?>> replies = exchange(provider, requests);
        check(replies.size() == requests.size(), "provider failure killed the transport");
        for (int i = 0; i < failures.size(); i++) {
            Map<?, ?> failure = replies.get(i * 2);
            toolReply(failure, failures.get(i), true);
            check(!json(failure).contains("sensitive"), "provider diagnostics leaked into a tool error");
            toolReply(replies.get(i * 2 + 1), "after-" + failures.get(i), false);
        }
        check(provider.requests.size() == requests.size(), "provider requests were duplicated or lost");
    }

    private static void planningRecovery() throws Exception {
        List<ModelRequest> calls = new ArrayList<>();
        String candidate = "{\"steps\":[{\"id\":\"status\",\"tool\":\"backend_status\","
                + "\"dependsOn\":[],\"arguments\":{}}]}";
        ModelBackend provider = new ModelBackend() {
            @Override public String id() { return "synthetic-agent"; }
            @Override public ModelResponse infer(ModelRequest request) {
                calls.add(request);
                if (request.prompt().endsWith("<task>\nruntime-failure\n</task>"))
                    throw new ArithmeticException("sensitive planning adapter diagnostics");
                try {
                    String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest
                            .getInstance("SHA-256").digest(request.prompt().getBytes(StandardCharsets.UTF_8)));
                    return new ModelResponse(candidate, id(), digest);
                } catch (java.security.NoSuchAlgorithmException impossible) {
                    throw new AssertionError(impossible);
                }
            }
        };
        List<String> requests = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String field : List.of("maxTokens", "timeoutSeconds")) {
            for (String value : List.of("1.5", "2147483648", "1e2147483647", "1e-2147483647", "0", "\"8\"")) {
                invalid.add("{\"task\":\"unused\",\"" + field + "\":" + value + "}");
            }
        }
        for (int i = 0; i < invalid.size(); i++) {
            requests.add(planning("plan-bad-" + i, invalid.get(i)));
            requests.add(planning("plan-good-" + i, "{\"task\":\"MiXeD नमस्ते " + i + "\"}"));
        }
        requests.add(planning("plan-runtime", "{\"task\":\"runtime-failure\"}"));
        requests.add(planning("plan-after-runtime", "{\"timeoutSeconds\":3e0,\"task\":\"Valid Again\",\"maxTokens\":8.00}"));
        String largeId = "i".repeat(StdioProtocol.MAX_RESPONSE_BYTES - 128);
        requests.add(planning(largeId, "{\"task\":\"bounded result\"}"));
        requests.add(planning("plan-after-budget", "{\"task\":\"Last\"}"));
        List<Map<?, ?>> replies = exchange(provider, requests);
        check(replies.size() == requests.size(), "planning errors killed or desynchronized MCP");
        for (int i = 0; i < invalid.size(); i++) {
            protocolError(replies.get(i * 2), "plan-bad-" + i, -32602);
            toolReply(replies.get(i * 2 + 1), "plan-good-" + i, false);
            Map<?, ?> result = asMap(replies.get(i * 2 + 1).get("result"));
            check(result.containsKey("structuredContent"), "current planning structured content lost");
            ModelRequest request = calls.get(i);
            check(request.prompt().endsWith("<task>\nMiXeD नमस्ते " + i + "\n</task>"),
                    "planning task case or Unicode changed");
            check(request.maxTokens() == 1024 && request.timeout().equals(Duration.ofSeconds(120)),
                    "current planning defaults changed");
        }
        int offset = invalid.size() * 2;
        toolReply(replies.get(offset), "plan-runtime", true);
        check(!json(replies.get(offset)).contains("sensitive"), "planning diagnostics leaked");
        toolReply(replies.get(offset + 1), "plan-after-runtime", false);
        check(calls.get(invalid.size() + 1).maxTokens() == 8
                && calls.get(invalid.size() + 1).timeout().equals(Duration.ofSeconds(3)),
                "integral planning numbers or named argument order changed");
        toolReply(replies.get(offset + 2), largeId, true);
        toolReply(replies.get(offset + 3), "plan-after-budget", false);
        check(calls.size() == invalid.size() + 4,
                "invalid planning arguments reached provider or a candidate step executed");
    }

    private static void responseBudget() throws Exception {
        for (String oversized : List.of("x".repeat(StdioProtocol.MAX_RESPONSE_BYTES),
                "\u0001".repeat(StdioProtocol.MAX_RESPONSE_BYTES / 6),
                "ह".repeat(StdioProtocol.MAX_RESPONSE_BYTES / 3))) {
            RecordingBackend provider = new RecordingBackend();
            List<Map<?, ?>> replies = exchange(provider, List.of(
                    infer(oversized, "{\"prompt\":\"Must not run\"}"),
                    infer("after-large-id", "{\"prompt\":\"Normal\"}")));
            check(replies.size() == 2, "oversized ID ended the stream");
            protocolError(replies.get(0), null, -32600);
            toolReply(replies.get(1), "after-large-id", false);
            check(provider.requests.size() == 1
                    && provider.requests.getFirst().prompt().equals("Normal"),
                    "oversized ID reached inference before response admission");
        }
        // A large but echoable ID remains valid. Only its oversized result is refused.
        String id = "i".repeat(StdioProtocol.MAX_RESPONSE_BYTES - 256);
        RecordingBackend provider = new RecordingBackend();
        List<Map<?, ?>> replies = exchange(provider, List.of(
                "{\"jsonrpc\":\"2.0\",\"id\":" + json(id) + ",\"method\":\"tools/list\"}",
                infer(id, "{\"prompt\":\"oversized-response\"}"),
                infer("after-budget", "{\"prompt\":\"Normal\"}")));
        check(replies.size() == 3, "response envelope overflow ended the stream");
        protocolError(replies.get(0), id, -32603);
        toolReply(replies.get(1), id, true);
        toolReply(replies.get(2), "after-budget", false);
        check(provider.requests.size() == 2, "response failure retried inference");
        initializationBudget();
    }

    private static void initializationBudget() throws Exception {
        // This ID fits both fixed error envelopes but not the larger initialize result.
        String id = "i".repeat(StdioProtocol.MAX_RESPONSE_BYTES - 128);
        String parameters = "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{}}";
        String input = "{\"jsonrpc\":\"2.0\",\"id\":" + json(id)
                + ",\"method\":\"initialize\",\"params\":" + parameters + "}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":\"retry\",\"method\":\"initialize\",\"params\":"
                + parameters + "}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":\"alive\",\"method\":\"ping\"}\n";
        RecordingBackend provider = new RecordingBackend();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpStdioServer.serve(provider, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);
        String[] lines = output.toString(StandardCharsets.UTF_8).split("\n");
        check(lines.length == 3, "failed initialization response ended the stream");
        protocolError(asMap(McpJson.parse(lines[0])), id, -32603);
        Map<?, ?> retry = asMap(McpJson.parse(lines[1]));
        check("retry".equals(retry.get("id")) && retry.containsKey("result"),
                "failed initialization response advanced negotiation state");
        Map<?, ?> ping = asMap(McpJson.parse(lines[2]));
        check("alive".equals(ping.get("id")) && ping.containsKey("result"),
                "session failed to initialize after bounded response refusal");
        check(provider.requests.isEmpty(), "initialization loaded the model");
    }

    private static void encodingBounds() throws Exception {
        for (Object value : List.of("", "ascii", "\"\\\b\f\n\r\t\u0000\u001f", "éह🦙",
                Map.of("key", List.of("é", "ह", "🦙", true, 123)),
                new java.math.BigDecimal("1e2147483647"))) {
            String encoded = McpJson.encode(value, 4096);
            int bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
            check(McpJson.encode(value, bytes).equals(encoded), "exact UTF-8 JSON budget changed output");
            rejectIo(() -> McpJson.encode(value, bytes - 1));
            check(McpJson.parse(encoded) != null, "encoder emitted invalid JSON");
        }
        rejectIo(() -> McpJson.encode("\ud800", 100));
        rejectIo(() -> McpJson.encode("\udc00", 100));
        rejectIo(() -> McpJson.encode(Map.of("\ud800", "x"), 100));
        rejectIo(() -> McpJson.encode("x", -1));
    }

    private static void boundedEncodingChild() throws Exception {
        String response = "\u0000".repeat(StdioProtocol.MAX_RESPONSE_BYTES);
        rejectIo(() -> McpJson.encode(response, StdioProtocol.MAX_RESPONSE_BYTES));
        System.out.println("bounded MCP encoding memory check passed");
    }

    private static void boundedEncodingMemory() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classes = Path.of(McpStdioServer.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        String tests = Path.of(McpStdioServerContractTestMain.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        Process probe = new ProcessBuilder(java, "-Xmx32m", "-cp", classes + File.pathSeparator + tests,
                McpStdioServerContractTestMain.class.getName(), "--bounded-encoding-child")
                .redirectErrorStream(true).start();
        try {
            if (!probe.waitFor(20, TimeUnit.SECONDS)) throw new AssertionError("encoding probe timed out");
            String output = new String(probe.getInputStream().readNBytes(64 * 1024), StandardCharsets.UTF_8);
            check(probe.exitValue() == 0 && output.contains("bounded MCP encoding memory check passed"),
                    "bounded MCP encoding probe failed: " + output);
        } finally {
            if (probe.isAlive()) {
                probe.destroyForcibly();
                probe.waitFor(5, TimeUnit.SECONDS);
            }
            probe.getInputStream().close();
            probe.getOutputStream().close();
            probe.getErrorStream().close();
        }
    }

    private static void agentRecovery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModelBackend provider = new ModelBackend() {
            @Override public String id() { return "synthetic-agent"; }
            @Override public ModelResponse infer(ModelRequest request) {
                if (calls.incrementAndGet() == 1) {
                    throw new ArithmeticException("sensitive agent adapter diagnostics");
                }
                try {
                    String receipt = java.util.HexFormat.of().formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(request.prompt().getBytes(StandardCharsets.UTF_8)));
                    return new ModelResponse("{\"type\":\"final\",\"text\":\"agent recovered\"}", id(), receipt);
                } catch (java.security.NoSuchAlgorithmException impossible) {
                    throw new AssertionError(impossible);
                }
            }
        };
        String prefix = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"agent_run\",\"arguments\":{";
        List<Map<?, ?>> replies = exchange(provider, List.of(
                prefix + "\"goal\":\"inspect\",\"maxTokens\":1e2147483647}},\"id\":\"agent-invalid\"}",
                prefix + "\"goal\":\"inspect\"}},\"id\":\"agent-failure\"}",
                prefix + "\"goal\":\"inspect again\"}},\"id\":\"agent-recovery\"}"), List.of());
        check(replies.size() == 3 && calls.get() == 2,
                "agent argument or provider failure ended the stream or invoked an extra model call");
        protocolError(replies.get(0), "agent-invalid", -32602);
        toolReply(replies.get(1), "agent-failure", true);
        toolReply(replies.get(2), "agent-recovery", false);
        check(!json(replies.get(1)).contains("sensitive"), "agent provider failure leaked diagnostics");
        check(json(replies.get(2)).contains("agent recovered"), "agent stream did not recover");
    }

    private static List<Map<?, ?>> exchange(ModelBackend provider, List<String> requests) throws Exception {
        return exchange(provider, requests, null);
    }

    private static List<Map<?, ?>> exchange(ModelBackend provider, List<String> requests,
                                          List<AgentTool> tools) throws Exception {
        String input = "{\"jsonrpc\":\"2.0\",\"id\":\"init\",\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{}}}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                + String.join("\n", requests) + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpStdioServer.serveTools(provider, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, tools);
        List<Map<?, ?>> replies = new ArrayList<>();
        for (String line : output.toString(StandardCharsets.UTF_8).split("\n")) {
            check(line.getBytes(StandardCharsets.UTF_8).length <= StdioProtocol.MAX_RESPONSE_BYTES,
                    "response exceeded its wire budget");
            Map<?, ?> reply = asMap(McpJson.parse(line));
            check("2.0".equals(reply.get("jsonrpc")), "JSON-RPC version changed");
            check(reply.containsKey("result") != reply.containsKey("error"), "invalid response envelope");
            replies.add(reply);
        }
        check("init".equals(replies.getFirst().get("id")), "initialization reply missing");
        return replies.subList(1, replies.size());
    }

    private static String infer(String id, String arguments) throws IOException {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + json(id) + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"infer_text\",\"arguments\":" + arguments + "}}";
    }

    private static String planning(String id, String arguments) throws IOException {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + json(id) + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"plan_steps\",\"arguments\":" + arguments + "}}";
    }

    private static String json(Object value) throws IOException {
        return McpJson.encode(value, StdioProtocol.MAX_PROMPT_BYTES);
    }

    private static void protocolError(Map<?, ?> reply, Object id, int code) {
        check(java.util.Objects.equals(reply.get("id"), id), "error response lost its admitted ID");
        check(!reply.containsKey("result") && asMap(reply.get("error")).get("code").toString()
                .equals(Integer.toString(code)), "incorrect protocol error");
    }

    private static void toolReply(Map<?, ?> reply, String id, boolean error) {
        check(reply.get("id").equals(id) && !reply.containsKey("error"), "tool response envelope changed");
        Map<?, ?> result = asMap(reply.get("result"));
        check(Boolean.valueOf(error).equals(result.get("isError")), "tool error flag incorrect");
        List<?> content = (List<?>) result.get("content");
        check(content.size() == 1 && asMap(content.getFirst()).get("type").equals("text")
                && asMap(content.getFirst()).get("text") instanceof String, "tool text content missing");
    }

    private static final class RecordingBackend implements ModelBackend {
        final List<ModelRequest> requests = new ArrayList<>();
        @Override public String id() { return "synthetic-jni"; }
        @Override public ModelResponse infer(ModelRequest request) throws IOException, TimeoutException {
            requests.add(request);
            return switch (request.prompt()) {
                case "io-failure" -> throw new IOException("sensitive IO diagnostics");
                case "timeout-failure" -> throw new TimeoutException("sensitive deadline diagnostics");
                case "runtime-failure" -> throw new ArithmeticException("sensitive native adapter diagnostics");
                case "null-response" -> null;
                case "wrong-identity" -> new ModelResponse("text", "other", "0".repeat(64));
                case "invalid-unicode" -> new ModelResponse("\ud800", id(), "0".repeat(64));
                case "oversized-response" -> new ModelResponse("x".repeat(StdioProtocol.MAX_RESPONSE_BYTES),
                        id(), "0".repeat(64));
                default -> new ModelResponse("reply: " + request.prompt(), id(), "0".repeat(64));
            };
        }
    }

    private static Map<?, ?> asMap(Object o) { return (Map<?, ?>) o; }
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static void rejectJson(String s) throws Exception { rejectIo(() -> McpJson.parse(s)); }
    private static void rejectIo(Action action) throws Exception {
        try { action.run(); throw new AssertionError("invalid input accepted"); }
        catch (IOException expected) { checks++; }
    }
    private static void check(boolean condition, String error) {
        checks++;
        if (!condition) throw new AssertionError(error);
    }
}
