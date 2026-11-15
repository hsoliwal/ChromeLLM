// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** No-network JSON-RPC and tool execution contracts for MCP stdio. */
public final class McpStdioServerContractTestMain {
    private static int checks;

    private McpStdioServerContractTestMain() { }

    public static void main(String[] args) throws Exception {
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
        rejectJson("[1,2,]");
        rejectJson("{\"x\":1,\"x\":2}");
        rejectIo(() -> McpStdioServer.serve(provider,
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream()));
        rejectIo(() -> McpStdioServer.serve(provider,
                new ByteArrayInputStream(new byte[]{(byte) 0xc3, (byte) 0x28, 10}),
                new ByteArrayOutputStream()));
        System.out.println("MCP stdio contract checks passed: " + checks);
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
