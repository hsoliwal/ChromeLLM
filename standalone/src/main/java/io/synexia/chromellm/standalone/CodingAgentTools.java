// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded tools for the existing headless repository service; it owns all source access. */
final class CodingAgentTools implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final Set<String> EXCLUDED = Set.of(".git", ".m3", "target", "build", "node_modules");
    private static final Set<String> PREVIEW_FIELDS = Set.of("path", "source_sha256", "replacement",
            "recipe_class", "recipe_source", "before_fixture", "after_fixture", "rationale");
    private static final ProxySelector DIRECT = new ProxySelector() {
        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
    };
    private final URI endpoint;
    private final String origin;
    private final String token;
    private final HttpClient client;
    private final List<AgentSession.Tool> tools;
    private final Set<CompletableFuture<?>> active = new HashSet<>();
    private final boolean atomPatternFirst;
    private final Map<String, SourceObservation> observations = new LinkedHashMap<>();
    private record SourceObservation(String sourceHash, String root) { }
    private boolean closed;

    CodingAgentTools(URI endpoint, String token) {
        validateEndpoint(endpoint);
        if (token == null || !token.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Coding agent token must contain 64 lowercase hexadecimal characters");
        }
        String ordering = System.getProperty("chromellm.agent.atomPatternFirst", "false");
        if (!ordering.equals("true") && !ordering.equals("false")) {
            throw new IllegalArgumentException("chromellm.agent.atomPatternFirst must be true or false");
        }
        this.atomPatternFirst = ordering.equals("true");
        this.origin = "http://127.0.0.1:" + endpoint.getPort();
        this.endpoint = URI.create(origin + "/");
        this.token = token;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .proxy(DIRECT).connectTimeout(Duration.ofSeconds(30)).build();
        this.tools = createTools();
    }

    List<AgentSession.Tool> tools() { return tools; }

    private static void validateEndpoint(URI endpoint) {
        if (endpoint == null || !"http".equals(endpoint.getScheme())
                || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort() < 1
                || endpoint.getPort() > 65535 || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !("".equals(endpoint.getRawPath()) || "/".equals(endpoint.getRawPath()))
                || !("127.0.0.1:" + endpoint.getPort()).equals(endpoint.getRawAuthority())) {
            throw new IllegalArgumentException("Coding agent endpoint must be http://127.0.0.1:port with no path or credentials");
        }
    }

    private List<AgentSession.Tool> createTools() {
        List<AgentSession.Tool> result = new ArrayList<>();
        result.add(emptyTool("repository_status", "Read repository service and index status.", "/api/status"));
        result.add(emptyTool("repository_files", "List admitted Java source paths and the inventory truncation flag.", "/api/files"));
        result.add(new AgentSession.Tool("source_read", "Read a Java source snapshot with its content hash."
                + (atomPatternFirst ? " The operator requires a source-bound canonical structural atom observation before preview; physical extraction and semantic pattern recipes remain separate." : ""),
                schema(Map.of("path", stringSchema(1024)), List.of("path")), (arguments, remaining) -> {
                    fields(arguments, Set.of("path"), Set.of("path"));
                    return sourceRead(path(arguments), remaining);
                }));
        Map<String, Object> searchProperties = new LinkedHashMap<>();
        searchProperties.put("query", stringSchema(256));
        searchProperties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 128));
        searchProperties.put("surface", Map.of("type", "string", "enum", List.of("raw", "java-code")));
        result.add(new AgentSession.Tool("source_search", "Search exact source text in the sealed repository index.",
                schema(searchProperties, List.of("query")), (arguments, remaining) -> {
                    fields(arguments, Set.of("query", "limit", "surface"), Set.of("query"));
                    Map<String, String> form = new LinkedHashMap<>();
                    form.put("q", text(arguments, "query", 256, false));
                    searchControls(arguments, form);
                    return request("GET", "/api/search", form, remaining, 0);
                }));
        Map<String, Object> batchProperties = new LinkedHashMap<>(searchProperties);
        batchProperties.remove("query");
        batchProperties.put("queries", Map.of("type", "array", "minItems", 1, "maxItems", 32,
                "uniqueItems", true, "items", stringSchema(256)));
        result.add(new AgentSession.Tool("source_search_batch", "Search multiple exact single-line patterns in one repository snapshot.",
                schema(batchProperties, List.of("queries")), (arguments, remaining) -> {
                    fields(arguments, Set.of("queries", "limit", "surface"), Set.of("queries"));
                    Object raw = arguments.get("queries");
                    if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > 32) throw invalid();
                    Set<String> unique = new HashSet<>();
                    List<String> queries = new ArrayList<>();
                    int totalBytes = 0;
                    for (Object value : values) {
                        String query = checkedText(value, 256, false);
                        if (query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0 || !unique.add(query)) throw invalid();
                        totalBytes += utf8Bytes(query);
                        if (totalBytes > 4096) throw invalid();
                        queries.add(query);
                    }
                    Map<String, String> form = new LinkedHashMap<>();
                    form.put("terms", String.join("\n", queries));
                    searchControls(arguments, form);
                    return request("POST", "/api/search-batch", form, remaining, 12 * 1024);
                }));
        Map<String, Object> categoryProperties = new LinkedHashMap<>();
        categoryProperties.put("platform", Map.of("type", "string", "enum",
                List.of("LEETCODE", "HACKERRANK", "GEEKSFORGEEKS")));
        categoryProperties.put("category", stringSchema(256));
        categoryProperties.put("prefix", stringSchema(256));
        categoryProperties.put("shape", Map.of("type", "string", "pattern", "^[A-Z][A-Z0-9_]{0,63}$",
                "description", "An exact canonical AlgorithmShape name, such as BINARY_SEARCH or AHO_CORASICK; the service validates membership."));
        categoryProperties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 64));
        result.add(new AgentSession.Tool("problem_categories",
                "Read category evidence with no arguments, or search reviewed challenge navigation by platform and category/prefix, or by algorithm shape. Select at most one of category, prefix and shape. Category/prefix require platform; limit alone is invalid. Results confer no solution-copy or promotion authority.",
                schema(categoryProperties, List.of()), (arguments, remaining) -> {
                    fields(arguments, Set.of("platform", "category", "prefix", "shape", "limit"), Set.of());
                    return request("GET", "/api/problem-categories", categoryControls(arguments), remaining, 0);
                }));
        result.add(new AgentSession.Tool("github_build_plan", "Read a configured GitHub build target plan.",
                schema(Map.of("target", Map.of("type", "string", "pattern", "^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")), List.of("target")),
                (arguments, remaining) -> {
                    fields(arguments, Set.of("target"), Set.of("target"));
                    String target = text(arguments, "target", 64, false);
                    if (!target.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) throw invalid();
                    return request("GET", "/api/github-build-plan", Map.of("target", target), remaining, 0);
                }));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("path", stringSchema(1024));
        preview.put("source_sha256", Map.of("type", "string", "pattern", "^[0-9a-f]{64}$"));
        preview.put("replacement", stringSchema(65536));
        preview.put("recipe_class", stringSchema(256));
        preview.put("recipe_source", stringSchema(65536));
        preview.put("before_fixture", stringSchema(65536));
        preview.put("after_fixture", stringSchema(65536));
        preview.put("rationale", Map.of("type", "string", "maxLength", 4096));
        result.add(new AgentSession.Tool("candidate_preview",
                "Create an isolated candidate with diff, structural lint and compiler checks. Read the returned gate results; tests and recipe proof remain separate."
                        + (atomPatternFirst ? " Requires this session's successful source_read for the exact path and source hash." : ""),
                schema(preview, List.copyOf(preview.keySet())), sessionScoped((arguments, remaining) -> {
                    fields(arguments, PREVIEW_FIELDS, PREVIEW_FIELDS);
                    Map<String, String> form = new LinkedHashMap<>();
                    form.put("path", path(arguments));
                    String hash = text(arguments, "source_sha256", 64, false);
                    if (!hash.matches("[0-9a-f]{64}")) throw invalid();
                    form.put("source_sha256", hash);
                    form.put("replacement", text(arguments, "replacement", 65536, false));
                    form.put("recipe_class", text(arguments, "recipe_class", 256, false));
                    form.put("recipe_source", text(arguments, "recipe_source", 65536, false));
                    form.put("before_fixture", text(arguments, "before_fixture", 65536, false));
                    form.put("after_fixture", text(arguments, "after_fixture", 65536, false));
                    form.put("rationale", text(arguments, "rationale", 4096, true));
                    String observation = null;
                    if (atomPatternFirst) {
                        synchronized (active) {
                            SourceObservation prior = observations.get(form.get("path"));
                            if (prior == null || prior.root() == null || !hash.equals(prior.sourceHash())) {
                                throw new IOException("Read a canonical atom-pattern observation for this exact source before preview");
                            }
                            observation = prior.root();
                        }
                    }
                    return request("POST", "/api/preview", form, remaining, 512 * 1024, observation);
                })));
        return List.copyOf(result);
    }

    private AgentSession.Handler sessionScoped(AgentSession.Handler delegate) {
        if (!atomPatternFirst) return delegate;
        return new AgentSession.Handler() {
            @Override public void beginSession() {
                synchronized (active) { observations.clear(); }
            }
            @Override public String execute(Map<String, Object> arguments, Duration remaining)
                    throws IOException, InterruptedException, TimeoutException {
                return delegate.execute(arguments, remaining);
            }
        };
    }

    private String sourceRead(String path, Duration timeout) throws IOException, InterruptedException, TimeoutException {
        if (!atomPatternFirst) return request("GET", "/api/file", Map.of("path", path), timeout, 0);
        SourceObservation pending = new SourceObservation(null, null);
        synchronized (active) {
            if (closed) throw new IOException("Coding agent tools are closed");
            observations.remove(path);
            if (observations.size() == 100) observations.remove(observations.keySet().iterator().next());
            observations.put(path, pending);
        }
        try {
            String result = request("GET", "/api/file", Map.of("path", path, "observation", "atom-pattern"), timeout, 0);
            Map<?, ?> response = (Map<?, ?>) McpJson.parse(result);
            if (!(response.get("source") instanceof String source) || !(response.get("sha256") instanceof String sourceHash)
                    || !path.equals(response.get("path")) || !sourceHash.matches("[0-9a-f]{64}")
                    || !sourceHash.equals(sourceHash(source))
                    || !(response.get("atomPatternObservation") instanceof Map<?, ?> observation)
                    || !"synexia.atom-pattern.observation.v1".equals(observation.get("schema"))
                    || !"COMPLETE".equals(observation.get("status"))
                    || !path.equals(observation.get("path")) || !sourceHash.equals(observation.get("source_sha256"))
                    || !Boolean.TRUE.equals(observation.get("lossless"))
                    || !"TYPE_AND_MEMBER_STRUCTURE".equals(observation.get("structuralScope"))
                    || !Boolean.FALSE.equals(observation.get("physicalExtractionExecuted"))
                    || !(observation.get("compositionRoot") instanceof String compositionRoot)
                    || !compositionRoot.matches("[0-9a-f]{64}")
                    || !(observation.get("observationRoot") instanceof String root) || !root.matches("[0-9a-f]{64}")) {
                throw new IOException("Canonical atom-pattern observation is unavailable or does not match the source");
            }
            synchronized (active) {
                if (closed || observations.get(path) != pending) {
                    throw new IOException("Canonical atom-pattern observation was superseded or tools closed");
                }
                observations.put(path, new SourceObservation(sourceHash, root));
            }
            return result;
        } catch (IOException | InterruptedException | TimeoutException failure) {
            synchronized (active) { if (observations.get(path) == pending) observations.remove(path); }
            throw failure;
        }
    }

    private static String sourceHash(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private AgentSession.Tool emptyTool(String name, String description, String route) {
        return new AgentSession.Tool(name, description, schema(Map.of(), List.of()), (arguments, remaining) -> {
            fields(arguments, Set.of(), Set.of());
            return request("GET", route, Map.of(), remaining, 0);
        });
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "object");
        value.put("properties", Collections.unmodifiableMap(new LinkedHashMap<>(properties)));
        value.put("required", List.copyOf(required));
        value.put("additionalProperties", false);
        return Collections.unmodifiableMap(value);
    }

    private static Map<String, Object> stringSchema(int limit) {
        return Map.of("type", "string", "minLength", 1, "maxLength", limit,
                "description", "Bounded to " + limit + " UTF-8 bytes.");
    }

    private static void fields(Map<String, Object> arguments, Set<String> permitted, Set<String> required) throws IOException {
        if (arguments == null || !arguments.keySet().containsAll(required)) throw invalid();
        for (String key : arguments.keySet()) {
            if (key == null || !permitted.contains(key)) throw invalid();
        }
    }

    private static String text(Map<String, Object> arguments, String key, int limit, boolean allowBlank) throws IOException {
        return checkedText(arguments.get(key), limit, allowBlank);
    }

    private static String checkedText(Object value, int limit, boolean allowBlank) throws IOException {
        if (!(value instanceof String text) || text.length() > limit || (!allowBlank && text.isBlank())
                || utf8Bytes(text) > limit) throw invalid();
        return text;
    }

    private static int utf8Bytes(String value) throws IOException {
        try {
            return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining();
        } catch (CharacterCodingException malformed) { throw invalid(); }
    }

    private static String path(Map<String, Object> arguments) throws IOException {
        String value = text(arguments, "path", 1024, false);
        if (!value.endsWith(".java") || value.startsWith("/") || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0 || value.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || EXCLUDED.contains(segment.toLowerCase(Locale.ROOT))) throw invalid();
        }
        return value;
    }

    private static void searchControls(Map<String, Object> arguments, Map<String, String> form) throws IOException {
        int limit = 25;
        if (arguments.containsKey("limit")) {
            Object value = arguments.get("limit");
            try {
                if (value instanceof BigDecimal number) limit = number.intValueExact();
                else if (value instanceof Integer number) limit = number;
                else if (value instanceof Long number) limit = Math.toIntExact(number);
                else throw invalid();
            } catch (ArithmeticException invalidNumber) { throw invalid(); }
            if (limit < 1 || limit > 128) throw invalid();
        }
        form.put("limit", Integer.toString(limit));
        if (arguments.containsKey("surface")) {
            String value = text(arguments, "surface", 9, false);
            if (!value.equals("raw") && !value.equals("java-code")) throw invalid();
            form.put("surface", value);
        }
    }

    private static Map<String, String> categoryControls(Map<String, Object> arguments) throws IOException {
        if (arguments.isEmpty()) return Map.of();
        Map<String, String> form = new LinkedHashMap<>();
        if (arguments.containsKey("platform")) {
            String platform = text(arguments, "platform", 16, false);
            if (!Set.of("LEETCODE", "HACKERRANK", "GEEKSFORGEEKS").contains(platform)) throw invalid();
            form.put("platform", platform);
        }
        int selectors = 0;
        for (String selector : List.of("category", "prefix", "shape")) {
            if (!arguments.containsKey(selector)) continue;
            if (++selectors > 1) throw invalid();
            String value = text(arguments, selector, selector.equals("shape") ? 64 : 256, false);
            if (selector.equals("shape")) {
                if (!value.matches("[A-Z][A-Z0-9_]{0,63}")) throw invalid();
            } else if (!form.containsKey("platform")) throw invalid();
            form.put(selector, value);
        }
        if (form.isEmpty()) throw invalid();
        form.put("limit", Integer.toString(boundedLimit(arguments, 64)));
        return form;
    }

    private static int boundedLimit(Map<String, Object> arguments, int maximum) throws IOException {
        int limit = 25;
        if (arguments.containsKey("limit")) {
            Object value = arguments.get("limit");
            try {
                if (value instanceof BigDecimal number) limit = number.intValueExact();
                else if (value instanceof Integer number) limit = number;
                else if (value instanceof Long number) limit = Math.toIntExact(number);
                else throw invalid();
            } catch (ArithmeticException invalidNumber) { throw invalid(); }
            if (limit < 1 || limit > maximum) throw invalid();
        }
        return limit;
    }

    private String request(String method, String route, Map<String, String> fields, Duration timeout, int maxFormBytes)
            throws IOException, InterruptedException, TimeoutException {
        return request(method, route, fields, timeout, maxFormBytes, null);
    }

    private String request(String method, String route, Map<String, String> fields, Duration timeout, int maxFormBytes,
                           String observation) throws IOException, InterruptedException, TimeoutException {
        interrupted();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Coding agent deadline must be positive");
        long budget;
        try { budget = timeout.toNanos(); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("Coding agent deadline is too large"); }
        long deadline = System.nanoTime() + budget;
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (encoded.length() != 0) encoded.append('&');
            encoded.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
        }
        if (method.equals("POST") && encoded.length() > maxFormBytes) throw invalid();
        String query = method.equals("GET") && !encoded.isEmpty() ? "?" + encoded : "";
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint.resolve(route + query))
                .timeout(Duration.ofNanos(remaining(deadline))).header("Accept", "application/json")
                .header("X-M3-Token", token);
        if (observation != null) builder.header("X-M3-Atom-Pattern-Observation", observation);
        if (method.equals("POST")) {
            builder.header("Origin", origin).header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(encoded.toString(), StandardCharsets.UTF_8));
        } else builder.GET();
        CompletableFuture<HttpResponse<byte[]>> pending;
        synchronized (active) {
            if (closed) throw new IOException("Coding agent tools are closed");
            pending = client.sendAsync(builder.build(), response -> new BoundedBodySubscriber(response.statusCode() == 200));
            active.add(pending);
        }
        try {
            HttpResponse<byte[]> response = pending.get(remaining(deadline), TimeUnit.NANOSECONDS);
            interrupted();
            if (response.statusCode() != 200) throw new IOException("Coding agent endpoint returned a non-success status");
            String result;
            try {
                result = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
            } catch (CharacterCodingException malformed) { throw new IOException("Coding agent response contains invalid UTF-8"); }
            Object parsed = McpJson.parse(result);
            if (!(parsed instanceof Map<?, ?>)) throw new IOException("Coding agent response must be a JSON object");
            if (result.contains(token) || containsToken(parsed)) throw new IOException("Coding agent response contains credential material");
            remaining(deadline);
            interrupted();
            synchronized (active) { if (closed) throw new IOException("Coding agent tools are closed"); }
            return result;
        } catch (TimeoutException exceeded) {
            pending.cancel(true);
            throw new TimeoutException("Coding agent request exceeded its deadline");
        } catch (InterruptedException cancelled) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedException("Coding agent request interrupted");
        } catch (CancellationException cancelled) {
            throw new IOException("Coding agent request cancelled or tools closed");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof HttpTimeoutException) throw new TimeoutException("Coding agent request exceeded its deadline");
            throw new IOException("Coding agent transport failed, returned a non-success status or exceeded its response limit");
        } finally {
            synchronized (active) { active.remove(pending); }
        }
    }

    private boolean containsToken(Object value) {
        if (value instanceof String text) return text.contains(token);
        if (value instanceof List<?> list) return list.stream().anyMatch(this::containsToken);
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (containsToken(entry.getKey()) || containsToken(entry.getValue())) return true;
            }
        }
        return false;
    }

    @Override public void close() {
        List<CompletableFuture<?>> pending;
        synchronized (active) {
            if (closed) return;
            closed = true;
            pending = List.copyOf(active);
            active.clear();
            observations.clear();
        }
        for (CompletableFuture<?> future : pending) future.cancel(true);
        client.shutdownNow();
    }

    private static long remaining(long deadline) throws TimeoutException {
        long value = deadline - System.nanoTime();
        if (value <= 0) throw new TimeoutException("Coding agent request exceeded its deadline");
        return value;
    }

    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Coding agent request interrupted");
    }

    private static IOException invalid() { return new IOException("Coding agent tool arguments violate the bounded schema"); }

    /** Completes after the full body, so the caller's timeout also covers stalled body delivery. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final boolean permitted;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        BoundedBodySubscriber(boolean permitted) { this.permitted = permitted; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription next) {
            if (subscription != null) { next.cancel(); return; }
            subscription = next;
            if (!permitted) {
                next.cancel();
                result.completeExceptionally(new IOException("Coding agent endpoint returned a non-success status"));
            } else next.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Coding agent response exceeded its limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(new IOException("Coding agent response body failed")); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
