// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/** Validates candidate read-only tool plans; does not execute model-proposed steps. */
final class AgentPlan {
    static final int MAX_STEPS = 16;
    private static final Set<String> STEP_FIELDS = Set.of("id", "tool", "dependsOn", "arguments");
    private static final Set<String> INFER_FIELDS = Set.of("prompt", "maxTokens", "timeoutSeconds");
    private final List<Step> steps;

    private AgentPlan(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    static AgentPlan compile(Object candidate) throws IOException {
        if (!(candidate instanceof Map<?, ?> root) || root.size() != 1
                || !root.containsKey("steps") || !(root.get("steps") instanceof List<?> raw)
                || raw.isEmpty() || raw.size() > MAX_STEPS) {
            throw invalid();
        }
        Map<String, Step> indexed = new HashMap<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> fields) || !fields.keySet().equals(STEP_FIELDS)
                    || !(fields.get("id") instanceof String id) || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")
                    || !(fields.get("tool") instanceof String tool)
                    || !(fields.get("dependsOn") instanceof List<?> dependencies)
                    || !(fields.get("arguments") instanceof Map<?, ?> arguments)) {
                throw invalid();
            }
            if (dependencies.size() > MAX_STEPS) throw invalid();
            Set<String> seen = new HashSet<>();
            List<String> dependsOn = new ArrayList<>();
            for (Object dependency : dependencies) {
                if (!(dependency instanceof String name) || !name.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")
                        || !seen.add(name) || name.equals(id)) throw invalid();
                dependsOn.add(name);
            }
            dependsOn.sort(String::compareTo);
            Map<String, Object> checked = arguments(tool, arguments);
            if (indexed.putIfAbsent(id, new Step(id, tool, List.copyOf(dependsOn), checked)) != null) {
                throw invalid();
            }
        }

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (Step step : indexed.values()) {
            inDegree.put(step.id(), step.dependsOn().size());
            for (String dependency : step.dependsOn()) {
                if (!indexed.containsKey(dependency)) throw invalid();
                outgoing.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(step.id());
            }
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        inDegree.forEach((id, count) -> { if (count == 0) ready.add(id); });
        List<Step> ordered = new ArrayList<>(indexed.size());
        while (!ready.isEmpty()) {
            String id = ready.remove();
            ordered.add(indexed.get(id));
            for (String dependent : outgoing.getOrDefault(id, List.of())) {
                int remaining = inDegree.merge(dependent, -1, Integer::sum);
                if (remaining == 0) ready.add(dependent);
            }
        }
        if (ordered.size() != indexed.size()) throw invalid();
        return new AgentPlan(ordered);
    }

    private static Map<String, Object> arguments(String tool, Map<?, ?> raw) throws IOException {
        if ("backend_status".equals(tool)) {
            if (!raw.isEmpty()) throw invalid();
            return Map.of();
        }
        if (!"infer_text".equals(tool) || raw.isEmpty() || !INFER_FIELDS.containsAll(raw.keySet())
                || !(raw.get("prompt") instanceof String prompt) || prompt.isBlank()) {
            throw invalid();
        }
        StdioProtocol.encode(prompt, 8192);
        TreeMap<String, Object> result = new TreeMap<>();
        result.put("prompt", prompt);
        for (String name : List.of("maxTokens", "timeoutSeconds")) {
            if (raw.containsKey(name)) {
                int value = integer(raw.get(name), name.equals("maxTokens") ? 32768 : 3600);
                result.put(name, value);
            }
        }
        return Map.copyOf(result);
    }

    private static int integer(Object input, int max) throws IOException {
        if (!(input instanceof BigDecimal number)) throw invalid();
        try {
            int value = number.intValueExact();
            if (value < 1 || value > max) throw invalid();
            return value;
        } catch (ArithmeticException invalid) {
            throw invalid();
        }
    }

    List<Step> steps() { return steps; }

    Map<String, Object> structuredContent() {
        List<Map<String, Object>> items = new ArrayList<>(steps.size());
        for (Step step : steps) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("id", step.id());
            fields.put("tool", step.tool());
            fields.put("dependsOn", step.dependsOn());
            fields.put("arguments", new TreeMap<>(step.arguments()));
            items.add(fields);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("steps", items);
        return result;
    }

    String json() throws IOException {
        return McpJson.encode(structuredContent(), 64 * 1024);
    }

    record Step(String id, String tool, List<String> dependsOn, Map<String, Object> arguments) { }

    private static IOException invalid() { return new IOException("Agent plan is invalid or unsafe"); }
}
