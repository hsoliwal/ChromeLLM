// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Loopback transport and admission contracts; no model, browser or external network. */
public final class CodingAgentToolsContractTestMain {
    private static final String TOKEN = "abcdef0123456789".repeat(4);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static int checks;
    private CodingAgentToolsContractTestMain() { }

    public static void main(String[] args) throws Exception {
        configuration();
        protocolAndSchemas();
        atomPatternOrdering();
        mcpAtomPatternIsolation();
        responseAdmission();
        deadlinesAndCancellation();
        directConnection();
        System.out.println("coding agent HTTP tool contract checks passed: " + checks);
    }

    private static void configuration() throws Exception {
        for (String endpoint : List.of("https://127.0.0.1:8080", "http://localhost:8080", "http://[::1]:8080",
                "http://127.0.0.2:8080", "http://127.0.0.1", "http://127.0.0.1:0", "http://127.0.0.1:65536",
                "http://127.0.0.1:8080/api", "http://127.0.0.1:8080/?a=1", "http://127.0.0.1:8080/#x",
                "http://user@127.0.0.1:8080", "http://127.0.0.1:08080", "http://127.0.0.1:8080//")) {
            reject(IllegalArgumentException.class, () -> new CodingAgentTools(URI.create(endpoint), TOKEN));
        }
        reject(IllegalArgumentException.class, () -> new CodingAgentTools(null, TOKEN));
        for (String token : List.of("", TOKEN.toUpperCase(), "a".repeat(63), "a".repeat(65), "x".repeat(64), TOKEN + "\n")) {
            reject(IllegalArgumentException.class, () -> new CodingAgentTools(URI.create("http://127.0.0.1:8080"), token));
        }
        reject(IllegalArgumentException.class, () -> new CodingAgentTools(URI.create("http://127.0.0.1:8080"), null));
        try (CodingAgentTools tools = new CodingAgentTools(URI.create("http://127.0.0.1:8080/"), TOKEN)) {
            check(tools.tools().size() == 8, "unexpected registered tool count");
            Set<String> expected = Set.of("repository_status", "repository_files", "source_read", "source_search",
                    "source_search_batch", "problem_categories", "github_build_plan", "candidate_preview");
            check(tools.tools().stream().map(AgentSession.Tool::name).collect(java.util.stream.Collectors.toSet()).equals(expected), "tool surface drift");
            for (AgentSession.Tool tool : tools.tools()) {
                check(Boolean.FALSE.equals(tool.inputSchema().get("additionalProperties")), "tool schema not closed");
                check(!tool.description().contains(TOKEN) && !McpJson.encode(tool.inputSchema(), 16384).contains(TOKEN), "credential in tool metadata");
            }
        }
    }

    @SuppressWarnings("try") // Explicit close is the lifecycle behavior under test.
    private static void protocolAndSchemas() throws Exception {
        try (Fixture service = new Fixture(); CodingAgentTools tools = new CodingAgentTools(service.endpoint(), TOKEN)) {
            String exact = " {\"value\":\"MixedCase हिन्दी & + \\n\",\"truncated\":false} \n";
            service.handler.set(exchange -> send(exchange, 200, exact.getBytes(StandardCharsets.UTF_8)));
            check(call(tools, "repository_status", Map.of()).equals(exact), "JSON observation was rewritten");
            assertRequest(service, "GET", "/api/status", Map.of(), false);
            call(tools, "repository_files", Map.of());
            assertRequest(service, "GET", "/api/files", Map.of(), false);
            String path = "src/हिन्दी/Mixed Case+Name.java";
            call(tools, "source_read", Map.of("path", path));
            assertRequest(service, "GET", "/api/file", Map.of("path", path), false);
            String query = "UpperCase हिन्दी+value & suffix";
            call(tools, "source_search", Map.of("surface", "java-code", "limit", new BigDecimal("128"), "query", query));
            assertRequest(service, "GET", "/api/search", Map.of("q", query, "limit", "128", "surface", "java-code"), false);
            check(service.last.get().rawQuery().startsWith("q=") && service.last.get().rawQuery().contains("&limit=128&surface=java-code"), "nondeterministic query ordering");
            call(tools, "source_search", Map.of("query", "a"));
            assertRequest(service, "GET", "/api/search", Map.of("q", "a", "limit", "25"), false);
            call(tools, "source_search_batch", Map.of("queries", List.of("Aa+Bb", "नाम&="), "surface", "raw"));
            assertRequest(service, "POST", "/api/search-batch", Map.of("terms", "Aa+Bb\nनाम&=", "limit", "25", "surface", "raw"), true);
            check(service.last.get().rawQuery() == null, "batch fields leaked into query");
            call(tools, "problem_categories", Map.of());
            assertRequest(service, "GET", "/api/problem-categories", Map.of(), false);
            call(tools, "problem_categories", Map.of("platform", "LEETCODE", "category", "Binary Search"));
            assertRequest(service, "GET", "/api/problem-categories",
                    Map.of("platform", "LEETCODE", "category", "Binary Search", "limit", "25"), false);
            call(tools, "problem_categories", Map.of("platform", "HACKERRANK", "prefix", "dyn", "limit", new BigDecimal("64")));
            assertRequest(service, "GET", "/api/problem-categories",
                    Map.of("platform", "HACKERRANK", "prefix", "dyn", "limit", "64"), false);
            check(service.last.get().rawQuery().equals("platform=HACKERRANK&prefix=dyn&limit=64"), "nondeterministic category query ordering");
            call(tools, "problem_categories", Map.of("platform", "GEEKSFORGEEKS"));
            assertRequest(service, "GET", "/api/problem-categories", Map.of("platform", "GEEKSFORGEEKS", "limit", "25"), false);
            call(tools, "problem_categories", Map.of("shape", "AHO_CORASICK", "limit", 1L));
            assertRequest(service, "GET", "/api/problem-categories", Map.of("shape", "AHO_CORASICK", "limit", "1"), false);
            call(tools, "problem_categories", Map.of("platform", "LEETCODE", "shape", "BINARY_SEARCH"));
            assertRequest(service, "GET", "/api/problem-categories",
                    Map.of("platform", "LEETCODE", "shape", "BINARY_SEARCH", "limit", "25"), false);
            String category = "हिन्दी + & = \uD83D\uDE80";
            call(tools, "problem_categories", Map.of("platform", "GEEKSFORGEEKS", "category", category));
            assertRequest(service, "GET", "/api/problem-categories",
                    Map.of("platform", "GEEKSFORGEEKS", "category", category, "limit", "25"), false);
            call(tools, "problem_categories", Map.of("platform", "LEETCODE", "prefix", "a".repeat(256)));
            assertRequest(service, "GET", "/api/problem-categories",
                    Map.of("platform", "LEETCODE", "prefix", "a".repeat(256), "limit", "25"), false);
            for (Map<String, Object> bad : List.<Map<String, Object>>of(
                    Map.of("limit", 1), Map.of("category", "array"), Map.of("prefix", "arr"),
                    Map.of("platform", "OTHER"), Map.of("platform", "leetcode"),
                    Map.of("platform", "LEETCODE", "category", "array", "prefix", "arr"),
                    Map.of("platform", "LEETCODE", "category", "array", "shape", "LINEAR_SCAN"),
                    Map.of("platform", "LEETCODE", "prefix", "arr", "shape", "LINEAR_SCAN"),
                    Map.of("platform", "LEETCODE", "category", ""),
                    Map.of("platform", "LEETCODE", "prefix", " "),
                    Map.of("platform", "LEETCODE", "category", "a".repeat(257)),
                    Map.of("platform", "LEETCODE", "prefix", "ह".repeat(86)),
                    Map.of("platform", "LEETCODE", "category", "\uD800"),
                    Map.of("shape", "binary-search"), Map.of("shape", "A".repeat(65)),
                    Map.of("platform", "LEETCODE", "limit", 0), Map.of("platform", "LEETCODE", "limit", 65),
                    Map.of("shape", "BFS", "limit", new BigDecimal("1.5")),
                    Map.of("shape", "BFS", "limit", Long.MAX_VALUE), Map.of("shape", "BFS", "limit", "1"),
                    Map.of("platform", "LEETCODE", "url", "https://leetcode.com/"),
                    Map.of("platform", "LEETCODE", "apply", true))) {
                rejectBeforeNetwork(service, () -> call(tools, "problem_categories", bad));
            }
            Map<String, Object> nullCategory = new LinkedHashMap<>();
            nullCategory.put("platform", "LEETCODE"); nullCategory.put("category", null);
            rejectBeforeNetwork(service, () -> call(tools, "problem_categories", nullCategory));
            call(tools, "github_build_plan", Map.of("target", "ChromeLLM-native_cpu.v1"));
            assertRequest(service, "GET", "/api/github-build-plan", Map.of("target", "ChromeLLM-native_cpu.v1"), false);
            Map<String, Object> candidate = candidate();
            call(tools, "candidate_preview", candidate);
            Map<String, String> wanted = new LinkedHashMap<>();
            candidate.forEach((key, value) -> wanted.put(key, (String) value));
            assertRequest(service, "POST", "/api/preview", wanted, true);

            List<Map<String, Object>> badSearch = new ArrayList<>();
            badSearch.add(Map.of());
            badSearch.add(Map.of("query", ""));
            badSearch.add(Map.of("query", " "));
            badSearch.add(Map.of("query", "a".repeat(257)));
            badSearch.add(Map.of("query", "ह".repeat(86)));
            badSearch.add(Map.of("query", "\uD800"));
            badSearch.add(Map.of("query", "a", "limit", 0));
            badSearch.add(Map.of("query", "a", "limit", 129));
            badSearch.add(Map.of("query", "a", "limit", new BigDecimal("1.5")));
            badSearch.add(Map.of("query", "a", "limit", Long.MAX_VALUE));
            badSearch.add(Map.of("query", "a", "limit", "1"));
            badSearch.add(Map.of("query", "a", "surface", "all"));
            badSearch.add(Map.of("query", "a", "url", "http://example.invalid"));
            Map<String, Object> nullValue = new LinkedHashMap<>();
            nullValue.put("query", null);
            badSearch.add(nullValue);
            Map<String, Object> nullKey = new LinkedHashMap<>();
            nullKey.put("query", "a");
            nullKey.put(null, true);
            badSearch.add(nullKey);
            for (Map<String, Object> bad : badSearch) rejectBeforeNetwork(service, () -> call(tools, "source_search", bad));
            for (String unsafe : List.of("/tmp/A.java", "../A.java", "a/../A.java", "a//A.java", "a/./A.java",
                    "src\\A.java", "C:A.java", ".git/A.java", "TARGET/A.java", "A.txt", "A\n.java")) {
                rejectBeforeNetwork(service, () -> call(tools, "source_read", Map.of("path", unsafe)));
            }
            rejectBeforeNetwork(service, () -> call(tools, "repository_status", Map.of("extra", true)));
            rejectBeforeNetwork(service, () -> call(tools, "source_read", Map.of()));
            rejectBeforeNetwork(service, () -> call(tools, "source_search_batch", Map.of("queries", List.of())));
            rejectBeforeNetwork(service, () -> call(tools, "source_search_batch", Map.of("queries", List.of("a", "a"))));
            rejectBeforeNetwork(service, () -> call(tools, "source_search_batch", Map.of("queries", List.of("a\nb"))));
            rejectBeforeNetwork(service, () -> call(tools, "source_search_batch", Map.of("queries", List.of("a\rb"))));
            rejectBeforeNetwork(service, () -> call(tools, "source_search_batch", Map.of("queries", List.of(1))));
            List<String> many = java.util.stream.IntStream.range(0, 33).mapToObj(i -> "q" + i).toList();
            rejectBeforeNetwork(service, () -> call(tools, "source_search_batch", Map.of("queries", many)));
            List<String> oversized = java.util.stream.IntStream.range(0, 20).mapToObj(i -> "a".repeat(250) + i).toList();
            rejectBeforeNetwork(service, () -> call(tools, "source_search_batch", Map.of("queries", oversized)));
            rejectBeforeNetwork(service, () -> call(tools, "github_build_plan", Map.of("target", "../all")));
            Map<String, Object> missing = new LinkedHashMap<>(candidate);
            missing.remove("recipe_source");
            rejectBeforeNetwork(service, () -> call(tools, "candidate_preview", missing));
            Map<String, Object> extra = new LinkedHashMap<>(candidate);
            extra.put("apply", true);
            rejectBeforeNetwork(service, () -> call(tools, "candidate_preview", extra));
            Map<String, Object> upperHash = new LinkedHashMap<>(candidate);
            upperHash.put("source_sha256", "A".repeat(64));
            rejectBeforeNetwork(service, () -> call(tools, "candidate_preview", upperHash));
            Map<String, Object> blank = new LinkedHashMap<>(candidate);
            blank.put("replacement", " ");
            rejectBeforeNetwork(service, () -> call(tools, "candidate_preview", blank));
            Map<String, Object> formOverflow = new LinkedHashMap<>(candidate);
            for (String field : List.of("replacement", "recipe_source", "before_fixture", "after_fixture")) formOverflow.put(field, "=".repeat(65536));
            rejectBeforeNetwork(service, () -> call(tools, "candidate_preview", formOverflow));
            reject(IllegalArgumentException.class, () -> find(tools, "repository_status").handler().execute(Map.of(), Duration.ZERO));
            tools.close();
            rejectBeforeNetwork(service, () -> call(tools, "repository_status", Map.of()));
        }
    }

    private static void responseAdmission() throws Exception {
        try (Fixture target = new Fixture(); Fixture service = new Fixture(); CodingAgentTools tools = new CodingAgentTools(service.endpoint(), TOKEN)) {
            service.handler.set(exchange -> {
                exchange.getResponseHeaders().set("Location", target.endpoint() + "api/status");
                send(exchange, 302, TOKEN.getBytes(StandardCharsets.UTF_8));
            });
            reject(IOException.class, () -> call(tools, "repository_status", Map.of()));
            check(target.requests.get() == 0, "redirect followed with credential");
            service.handler.set(exchange -> send(exchange, 403, TOKEN.getBytes(StandardCharsets.UTF_8)));
            reject(IOException.class, () -> call(tools, "repository_status", Map.of()));
            for (byte[] body : List.of(new byte[] {(byte) 0xc3, 0x28}, "{".getBytes(StandardCharsets.UTF_8),
                    "[]".getBytes(StandardCharsets.UTF_8), "{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8),
                    "{} trailing".getBytes(StandardCharsets.UTF_8), ("{\"value\":\"" + TOKEN + "\"}").getBytes(StandardCharsets.UTF_8),
                    ("{\"value\":\"\\u0061" + TOKEN.substring(1) + "\"}").getBytes(StandardCharsets.UTF_8),
                    ("{\"value\":\"" + "a".repeat(128 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8))) {
                service.handler.set(exchange -> send(exchange, 200, body));
                reject(IOException.class, () -> call(tools, "repository_status", Map.of()));
            }
            service.handler.set(exchange -> send(exchange, 200, "{\"valid\":true}".getBytes(StandardCharsets.UTF_8)));
            check(call(tools, "repository_status", Map.of()).equals("{\"valid\":true}"), "adapter did not recover after rejected responses");
        }
    }

    /** Synthetic transport envelopes exercise admission; the service tests use the real extractor. */
    private static void atomPatternOrdering() throws Exception {
        String property = "chromellm.agent.atomPatternFirst";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "true");
            try (Fixture service = new Fixture(); CodingAgentTools tools = new CodingAgentTools(service.endpoint(), TOKEN)) {
                Map<String, Object> candidate = new LinkedHashMap<>(candidate());
                String source = "class Example {}\n";
                String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(source.getBytes(StandardCharsets.UTF_8)));
                String root = "a".repeat(64);
                candidate.put("source_sha256", hash);
                rejectBeforeNetwork(service, () -> call(tools, "candidate_preview", candidate));
                Map<String, Object> observation = new LinkedHashMap<>();
                observation.put("schema", "synexia.atom-pattern.observation.v1");
                observation.put("status", "COMPLETE");
                observation.put("path", "src/Example.java");
                observation.put("source_sha256", hash);
                observation.put("observationRoot", root);
                observation.put("compositionRoot", "b".repeat(64));
                observation.put("lossless", true);
                observation.put("structuralScope", "TYPE_AND_MEMBER_STRUCTURE");
                observation.put("physicalExtractionExecuted", false);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("path", "src/Example.java"); response.put("sha256", hash); response.put("source", source);
                response.put("atomPatternObservation", observation);
                service.handler.set(exchange -> send(exchange, 200, McpJson.encode(response, 16384).getBytes(StandardCharsets.UTF_8)));
                call(tools, "source_read", Map.of("path", "src/Example.java"));
                assertRequest(service, "GET", "/api/file", Map.of("path", "src/Example.java", "observation", "atom-pattern"), false);
                check(service.last.get().observation() == null, "source read must not submit a claimed observation");
                service.handler.set(exchange -> send(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8)));
                call(tools, "candidate_preview", candidate);
                check(root.equals(service.last.get().observation()), "preview did not bind the retained observation");
                call(tools, "candidate_preview", candidate);
                check(root.equals(service.last.get().observation()), "repeated preview lost its observation binding");
                Map<String, Object> stale = new LinkedHashMap<>(candidate);
                stale.put("source_sha256", "0".repeat(64));
                rejectBeforeNetwork(service, () -> call(tools, "candidate_preview", stale));
                Map<String, Object> otherPath = new LinkedHashMap<>(candidate);
                otherPath.put("path", "src/Other.java");
                rejectBeforeNetwork(service, () -> call(tools, "candidate_preview", otherPath));
                for (String invalid : List.of("{}", McpJson.encode(Map.of("path", "src/Example.java", "sha256", hash,
                        "source", source, "atomPatternObservation", Map.of("status", "UNSUPPORTED_PARSE")), 16384))) {
                    service.handler.set(exchange -> send(exchange, 200, invalid.getBytes(StandardCharsets.UTF_8)));
                    reject(IOException.class, () -> call(tools, "source_read", Map.of("path", "src/Example.java")));
                    rejectBeforeNetwork(service, () -> call(tools, "candidate_preview", candidate));
                }
                response.put("source", source + "// changed\n");
                service.handler.set(exchange -> send(exchange, 200, McpJson.encode(response, 16384).getBytes(StandardCharsets.UTF_8)));
                reject(IOException.class, () -> call(tools, "source_read", Map.of("path", "src/Example.java")));
                rejectBeforeNetwork(service, () -> call(tools, "candidate_preview", candidate));
                response.put("source", source);
                call(tools, "source_read", Map.of("path", "src/Example.java"));
                service.handler.set(exchange -> send(exchange, 503, "{}".getBytes(StandardCharsets.UTF_8)));
                reject(IOException.class, () -> call(tools, "source_read", Map.of("path", "src/Example.java")));
                rejectBeforeNetwork(service, () -> call(tools, "candidate_preview", candidate));
            }
            for (String invalid : List.of("TRUE", "1", "yes", "")) {
                System.setProperty(property, invalid);
                reject(IllegalArgumentException.class, () -> new CodingAgentTools(URI.create("http://127.0.0.1:8080"), TOKEN));
            }
            System.setProperty(property, "false");
            try (Fixture service = new Fixture(); CodingAgentTools tools = new CodingAgentTools(service.endpoint(), TOKEN)) {
                call(tools, "candidate_preview", candidate());
                check(service.last.get().observation() == null, "legacy preview changed its authority contract");
                call(tools, "source_read", Map.of("path", "src/Example.java"));
                check(service.last.get().values().equals(Map.of("path", "src/Example.java")), "legacy source read opted in");
            }
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
    }

    private static void mcpAtomPatternIsolation() throws Exception {
        String key = "chromellm.agent.atomPatternFirst", previous = System.getProperty(key);
        System.setProperty(key, "true");
        try {
            for (boolean firstRunFails : List.of(false, true)) {
                try (Fixture service = new Fixture(); CodingAgentTools tools = new CodingAgentTools(service.endpoint(), TOKEN)) {
                    String source = "class Example {}\n";
                    String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(source.getBytes(StandardCharsets.UTF_8)));
                    Map<String, Object> candidate = new LinkedHashMap<>(candidate()); candidate.put("source_sha256", hash);
                    Map<String, Object> observation = Map.of("schema", "synexia.atom-pattern.observation.v1",
                            "status", "COMPLETE", "path", "src/Example.java", "source_sha256", hash,
                            "observationRoot", "a".repeat(64), "compositionRoot", "b".repeat(64), "lossless", true,
                            "structuralScope", "TYPE_AND_MEMBER_STRUCTURE", "physicalExtractionExecuted", false);
                    String read = McpJson.encode(Map.of("path", "src/Example.java", "sha256", hash, "source", source,
                            "atomPatternObservation", observation), 16384);
                    service.handler.set(exchange -> send(exchange, 200, (exchange.getRequestURI().getPath().equals("/api/file")
                            ? read : "{}").getBytes(StandardCharsets.UTF_8)));
                    List<String> script = List.of(
                            McpJson.encode(Map.of("type", "tool", "name", "source_read", "arguments", Map.of("path", "src/Example.java")), 16384),
                            firstRunFails ? "{}" : "{\"type\":\"final\",\"answer\":\"first source observed\"}",
                            McpJson.encode(Map.of("type", "tool", "name", "candidate_preview", "arguments", candidate), 16384),
                            "{\"type\":\"final\",\"answer\":\"unexpected cross-run preview\"}");
                    AtomicInteger calls = new AtomicInteger();
                    ModelBackend backend = new ModelBackend() {
                        @Override public String id() { return "mcp-atom-order-fixture"; }
                        @Override public ModelResponse infer(ModelRequest request) {
                            try {
                                String promptHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(request.prompt().getBytes(StandardCharsets.UTF_8)));
                                return new ModelResponse(script.get(calls.getAndIncrement()), id(), promptHash);
                            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
                        }
                    };
                    String input = McpJson.encode(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params",
                            Map.of("protocolVersion", McpStdioServer.VERSION, "capabilities", Map.of(), "clientInfo", Map.of())), 16384)
                            + "\n{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n";
                    for (int id : List.of(2, 3)) input += McpJson.encode(Map.of("jsonrpc", "2.0", "id", id,
                            "method", "tools/call", "params", Map.of("name", "run_agent", "arguments",
                                    Map.of("goal", "Independent run " + id))), 16384) + "\n";
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                    McpStdioServer.serve(backend, new java.io.ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                            output, tools.tools(), new AgentSession.Limits(4, 512, 65536, 16384, Duration.ofSeconds(10)));
                    List<String> replies = output.toString(StandardCharsets.UTF_8).lines().toList();
                    check(replies.size() == 3, "two-run MCP fixture did not complete");
                    Map<?, ?> first = (Map<?, ?>) ((Map<?, ?>) McpJson.parse(replies.get(1))).get("result");
                    Map<?, ?> second = (Map<?, ?>) ((Map<?, ?>) McpJson.parse(replies.get(2))).get("result");
                    check(Boolean.valueOf(firstRunFails).equals(first.get("isError")), "first run did not establish the tested completion state");
                    check(Boolean.TRUE.equals(second.get("isError")), "MCP run reused an earlier run's source observation");
                    check(service.requests.get() == 1 && calls.get() == 3, "cross-run preview reached the HTTP service or continued inference");
                }
            }
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    @SuppressWarnings("try") // Closing an active request is the behavior under test.
    private static void deadlinesAndCancellation() throws Exception {
        try (Fixture service = new Fixture(); CodingAgentTools tools = new CodingAgentTools(service.endpoint(), TOKEN)) {
            call(tools, "repository_status", Map.of());
            CountDownLatch headers = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            service.handler.set(exchange -> stall(exchange, headers, release));
            long started = System.nanoTime();
            try {
                reject(TimeoutException.class, () -> find(tools, "repository_status").handler().execute(Map.of(), Duration.ofMillis(400)));
                check(headers.getCount() == 0, "stalled body fixture was not reached");
                check(System.nanoTime() - started < Duration.ofSeconds(3).toNanos(), "body timeout was not bounded");
            } finally { release.countDown(); }

            CountDownLatch interruptHeaders = new CountDownLatch(1);
            CountDownLatch interruptRelease = new CountDownLatch(1);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            AtomicReference<Boolean> retained = new AtomicReference<>(false);
            service.handler.set(exchange -> stall(exchange, interruptHeaders, interruptRelease));
            Thread caller = Thread.ofVirtual().start(() -> {
                try { call(tools, "repository_status", Map.of()); outcome.set(new AssertionError("interrupted request succeeded")); }
                catch (Throwable failure) { outcome.set(failure); retained.set(Thread.currentThread().isInterrupted()); }
            });
            try {
                check(interruptHeaders.await(3, TimeUnit.SECONDS), "interruption fixture was not reached");
                caller.interrupt();
                caller.join(2000);
                check(!caller.isAlive() && outcome.get() instanceof InterruptedException, "interruption did not cancel body request");
                check(retained.get(), "interruption status was cleared");
            } finally { interruptRelease.countDown(); caller.interrupt(); }

            int before = service.requests.get();
            Thread.currentThread().interrupt();
            try { reject(InterruptedException.class, () -> call(tools, "repository_status", Map.of())); }
            finally { Thread.interrupted(); }
            check(service.requests.get() == before, "preinterrupted call reached service");

            CountDownLatch closeHeaders = new CountDownLatch(1);
            CountDownLatch closeRelease = new CountDownLatch(1);
            AtomicReference<Throwable> closedResult = new AtomicReference<>();
            service.handler.set(exchange -> stall(exchange, closeHeaders, closeRelease));
            Thread closingCaller = Thread.ofVirtual().start(() -> {
                try { call(tools, "repository_status", Map.of()); closedResult.set(new AssertionError("closed request succeeded")); }
                catch (Throwable failure) { closedResult.set(failure); }
            });
            try {
                check(closeHeaders.await(3, TimeUnit.SECONDS), "close fixture was not reached");
                tools.close();
                closingCaller.join(2000);
                check(!closingCaller.isAlive() && closedResult.get() instanceof IOException, "close did not cancel active request");
            } finally { closeRelease.countDown(); closingCaller.interrupt(); }
        }
    }

    private static void directConnection() throws Exception {
        ProxySelector previous = ProxySelector.getDefault();
        AtomicInteger selected = new AtomicInteger();
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                selected.incrementAndGet();
                return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1)));
            }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        });
        try (Fixture service = new Fixture(); CodingAgentTools tools = new CodingAgentTools(service.endpoint(), TOKEN)) {
            call(tools, "repository_status", Map.of());
            check(selected.get() == 0, "ambient proxy used for authenticated loopback request");
        } finally { ProxySelector.setDefault(previous); }
    }

    private static Map<String, Object> candidate() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", "src/Example.java");
        result.put("source_sha256", "0".repeat(64));
        result.put("replacement", "class Example { String text = \"हिन्दी+&\"; }\n");
        result.put("recipe_class", "example.RewriteRecipe");
        result.put("recipe_source", "class RewriteRecipe {}\n");
        result.put("before_fixture", "class Example {}\n");
        result.put("after_fixture", "class Example { String text = \"हिन्दी+&\"; }\n");
        result.put("rationale", "Preserve API; exact named fields = yes & Unicode.");
        return result;
    }

    private static void assertRequest(Fixture fixture, String method, String path, Map<String, String> expected, boolean post) {
        Seen seen = fixture.last.get();
        check(seen.method().equals(method) && seen.path().equals(path), "wrong method or route");
        check(TOKEN.equals(seen.token()), "missing credential header");
        check(post ? fixture.origin().equals(seen.origin()) : seen.origin() == null, "incorrect Origin header");
        check(seen.values().equals(expected), "named fields or original text changed");
        if (post) check(seen.contentType().equals("application/x-www-form-urlencoded; charset=utf-8"), "incorrect form media type");
    }

    private static AgentSession.Tool find(CodingAgentTools tools, String name) {
        return tools.tools().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }
    private static String call(CodingAgentTools tools, String name, Map<String, Object> arguments) throws Exception {
        return find(tools, name).handler().execute(arguments, TIMEOUT);
    }
    private static void rejectBeforeNetwork(Fixture service, Checked action) throws Exception {
        int before = service.requests.get();
        reject(IOException.class, action);
        check(service.requests.get() == before, "invalid arguments reached the service");
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void reject(Class<? extends Exception> type, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + type.getSimpleName()); }
        catch (Exception expected) {
            if (!type.isInstance(expected)) throw expected;
            check(expected.getMessage() == null || !expected.getMessage().contains(TOKEN), "credential in exception");
            check(expected.getCause() == null, "transport or credential-bearing cause escaped");
            checks++;
        }
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var stream = exchange.getResponseBody()) { stream.write(body); }
    }
    private static void stall(HttpExchange exchange, CountDownLatch started, CountDownLatch release) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        try (var stream = exchange.getResponseBody()) {
            stream.write('{');
            stream.flush();
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws IOException; }
    private record Seen(String method, String path, String rawQuery, String token, String origin,
                        String contentType, Map<String, String> values, String observation) { }
    private static final class Fixture implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<Seen> last = new AtomicReference<>();
        final AtomicReference<Handler> handler = new AtomicReference<>(exchange -> send(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8)));
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final HttpServer server;
        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                try {
                    String form = exchange.getRequestMethod().equals("POST")
                            ? new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                            : exchange.getRequestURI().getRawQuery();
                    Map<String, String> values = new LinkedHashMap<>();
                    if (form != null && !form.isEmpty()) {
                        for (String part : form.split("&")) {
                            int equals = part.indexOf('=');
                            values.put(URLDecoder.decode(part.substring(0, equals), StandardCharsets.UTF_8),
                                    URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8));
                        }
                    }
                    last.set(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                            exchange.getRequestHeaders().getFirst("X-M3-Token"), exchange.getRequestHeaders().getFirst("Origin"),
                            exchange.getRequestHeaders().getFirst("Content-Type"), values,
                            exchange.getRequestHeaders().getFirst("X-M3-Atom-Pattern-Observation")));
                    handler.get().handle(exchange);
                } finally { exchange.close(); }
            });
            server.start();
        }
        String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        URI endpoint() { return URI.create(origin() + "/"); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
