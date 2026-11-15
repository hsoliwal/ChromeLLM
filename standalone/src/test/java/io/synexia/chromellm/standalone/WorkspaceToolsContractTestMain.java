/*
 * Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeoutException;

/** Real-filesystem and independent sort/search-oracle checks; executable without test dependencies. */
public final class WorkspaceToolsContractTestMain {
    private static int checks;
    private WorkspaceToolsContractTestMain() { }

    public static void main(String[] ignored) throws Exception {
        Path root = Files.createTempDirectory("chromellm-workspace-");
        try {
            // Reflection allows the exact same test to demonstrate missing behavior on the baseline.
            Class.forName("io.synexia.chromellm.standalone.WorkspaceTools");
            basic(root);
            ranking(root);
            safety(root);
            budgets(root);
            cancellation(root);
            portable(root);
            boundedOutput(root);
            directoryIterationFailure(root);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("workspace tool contract checks passed: " + checks);
    }

    private static void basic(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("basic"));
        Files.writeString(dir.resolve("a.txt"), "α😀aaaa\r\nsecond\rthird\nlast");
        Object tools = workspace(dir);
        check(toolList(tools).size() == 3, "built-in tools");
        Map<?, ?> result = call(tools, "workspace.search", Map.of("query", "aa"));
        Map<?, ?> hit = (Map<?, ?>) ((List<?>) result.get("matches")).getFirst();
        check(hit.get("occurrences").equals(3), "overlapping literal matches");
        check(hit.get("firstOffset").equals(3), "UTF-16 offset");
        check(hit.get("firstLine").equals(1), "first line");
        check(hit.get("excerpt").toString().contains("😀aaaa"), "source excerpt");
        Map<?, ?> lf = (Map<?, ?>) ((List<?>) call(tools, "workspace.search", Map.of("query", "\n")).get("matches")).getFirst();
        check(lf.get("firstLine").equals(1), "CRLF is one line separator even when matching its LF");
        check(((List<?>) call(tools, "workspace.search", Map.of("query", "Α")).get("matches")).isEmpty(), "case sensitive");
        check(((List<?>) call(tools, "workspace.search", Map.of("query", "[.*]")).get("matches")).isEmpty(), "literal metacharacters");
        Map<?, ?> read = call(tools, "workspace.read", Map.of("path", "a.txt", "startLine", 2, "maxLines", 2));
        check(read.get("lines").equals(List.of("second", "third")), "CR/LF line semantics");
        check(Boolean.TRUE.equals(read.get("truncated")), "read truncation");
        var parse = Class.forName("io.synexia.chromellm.standalone.McpJson").getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> jsonArgs = (Map<String, Object>) parse.invoke(null, "{\"path\":\"a.txt\",\"startLine\":2,\"maxLines\":2}");
        check(call(tools, "workspace.read", jsonArgs).get("lines").equals(List.of("second", "third")), "McpJson exact numeric arguments");
        for (String number : List.of("1.5", "1e1000", "0", "201"))
            fails(IOException.class, () -> call(tools, "workspace.read", Map.of("path", "a.txt", "maxLines", new java.math.BigDecimal(number))));
        check(call(tools, "workspace.read", Map.of("path", "a.txt", "startLine", 20)).get("lines").equals(List.of()), "past EOF");
        for (Map<String, Object> args : List.<Map<String, Object>>of(Map.of("query", ""), Map.of("query", "a", "maxResults", 101),
                Map.of("query", "a", "extra", true), Map.of("query", "a", "maxResults", 1.5))) {
            fails(IOException.class, () -> call(tools, "workspace.search", args));
        }
        fails(IOException.class, () -> call(tools, "workspace.read", Map.of("path", "a.txt", "maxLines", 201)));
        fails(IOException.class, () -> call(tools, "workspace.read", Map.of("path", "a.txt", "startLine", 0)));
    }

    private static void ranking(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("rank"));
        Random random = new Random(7731);
        List<Map<String, Object>> oracle = new ArrayList<>();
        String query = "a😀a";
        for (int i = 0; i < 137; i++) {
            String name = String.format("%03d.txt", i);
            StringBuilder text = new StringBuilder("prefix\n");
            for (int j = 0, n = random.nextInt(50); j < n; j++) text.append(random.nextBoolean() ? "a😀" : "ba");
            text.append('a');
            String value = text.toString();
            Files.writeString(dir.resolve(name), value);
            int count = 0;
            for (int p = 0; p + query.length() <= value.length(); p++) {
                boolean equal = true;
                for (int q = 0; q < query.length(); q++) equal &= value.charAt(p + q) == query.charAt(q);
                if (equal) count++;
            }
            if (count > 0) oracle.add(Map.of("path", name, "occurrences", count));
        }
        oracle.sort(Comparator.<Map<String, Object>>comparingInt(m -> (Integer) m.get("occurrences")).reversed()
                .thenComparing(m -> (String) m.get("path")));
        Object tools = workspace(dir);
        for (int k : new int[]{1, 3, 20, 100}) {
            List<?> actual = (List<?>) call(tools, "workspace.search", Map.of("query", query, "maxResults", k)).get("matches");
            check(actual.size() == Math.min(k, oracle.size()), "top-k size");
            for (int i = 0; i < actual.size(); i++) {
                Map<?, ?> hit = (Map<?, ?>) actual.get(i);
                check(hit.get("path").equals(oracle.get(i).get("path"))
                        && hit.get("occurrences").equals(oracle.get(i).get("occurrences")), "full-sort oracle rank " + i);
            }
        }
        List<?> listed = (List<?>) call(tools, "workspace.list", Map.of("maxResults", 3)).get("files");
        check(listed.equals(List.of("000.txt", "001.txt", "002.txt")), "list deterministic ascending top-k");
    }

    private static void safety(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("safe"));
        Files.writeString(dir.resolve("visible.txt"), "needle");
        for (String name : List.of(".env", ".env.production", "secret.pem", "secret.key", "id_rsa")) Files.writeString(dir.resolve(name), "needle");
        for (String name : List.of(".git", "target", "node_modules")) {
            Files.createDirectory(dir.resolve(name));
            Files.writeString(dir.resolve(name).resolve("secret"), "needle");
        }
        Files.createSymbolicLink(dir.resolve("outside"), root.resolve("basic"));
        Files.createSymbolicLink(dir.resolve("linked.txt"), dir.resolve("visible.txt"));
        Object tools = workspace(dir);
        check(call(tools, "workspace.list", Map.of()).get("files").equals(List.of("visible.txt")), "discovery excludes links and denied files");
        for (String name : List.of("../basic/a.txt", dir.resolve("visible.txt").toString(), "outside/a.txt", "linked.txt", ".env", "secret.pem", "id_rsa", "target/secret")) {
            fails(IOException.class, () -> call(tools, "workspace.read", Map.of("path", name)));
        }
        Files.write(dir.resolve("bad.txt"), new byte[]{(byte) 0xc3, 0x28});
        fails(IOException.class, () -> call(tools, "workspace.read", Map.of("path", "bad.txt")));
        Files.writeString(dir.resolve("binary.dat"), "needle\u0000binary");
        Map<?, ?> skipped = call(tools, "workspace.search", Map.of("query", "needle"));
        check(skipped.get("skippedFiles").equals(2), "discovery skips invalid UTF-8 and binary entries");
        check(Boolean.TRUE.equals(skipped.get("truncated")) && Boolean.FALSE.equals(skipped.get("scanComplete")), "unsupported sources make scope partial");
        check(((Map<?, ?>) skipped.get("skipReasons")).get("invalidUtf8").equals(1), "invalid UTF-8 skip reason");
        check(((Map<?, ?>) skipped.get("skipReasons")).get("binary").equals(1), "binary skip reason");
        check(((Number) skipped.get("scannedBytes")).longValue() >= 16, "skipped source bytes charged");
        Files.delete(dir.resolve("binary.dat"));
        Files.delete(dir.resolve("bad.txt"));
        Files.write(dir.resolve("large.txt"), new byte[1024 * 1024 + 1]);
        fails(IOException.class, () -> call(tools, "workspace.read", Map.of("path", "large.txt")));
        check(Boolean.TRUE.equals(call(tools, "workspace.search", Map.of("query", "needle")).get("truncated")), "oversized source is explicit");
        fails(IOException.class, () -> workspace(dir.resolve("outside")));
    }

    private static void budgets(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("entries"));
        for (int i = 0; i < 4100; i++) Files.writeString(dir.resolve("f" + i), "a");
        Map<?, ?> list = call(workspace(dir), "workspace.list", Map.of("maxResults", 1));
        check(Boolean.TRUE.equals(list.get("truncated")), "entry cap reported");
        check(((Number) list.get("scannedEntries")).intValue() <= 4096, "entry cap enforced independent of result cap");
        Path bytes = Files.createDirectory(root.resolve("bytes"));
        String block = "z".repeat(1024 * 1024);
        for (int i = 0; i < 17; i++) Files.writeString(bytes.resolve("f" + i), block);
        Map<?, ?> search = call(workspace(bytes), "workspace.search", Map.of("query", "z", "maxResults", 1));
        check(Boolean.TRUE.equals(search.get("truncated")), "aggregate byte cap reported");
        check(((Number) search.get("scannedBytes")).longValue() <= 16L * 1024 * 1024, "aggregate byte cap enforced");
        Path deep = Files.createDirectory(root.resolve("deep"));
        Path cursor = deep;
        for (int i = 0; i < 66; i++) cursor = Files.createDirectory(cursor.resolve("d"));
        Files.writeString(cursor.resolve("hidden"), "a");
        check(Boolean.TRUE.equals(call(workspace(deep), "workspace.list", Map.of()).get("truncated")), "depth cap reported");
    }

    private static void cancellation(Path root) throws Exception {
        Object tools = workspace(root.resolve("basic"));
        Thread.currentThread().interrupt();
        try {
            fails(InterruptedException.class, () -> call(tools, "workspace.list", Map.of()));
            check(Thread.currentThread().isInterrupted(), "interrupt flag retained");
        } finally { Thread.interrupted(); }
        Object deadline = deadline(Duration.ofNanos(1));
        fails(TimeoutException.class, () -> invoke(tool(tools, "workspace.list"), "execute", Map.of(), deadline));
    }

    private static void portable(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("portable"));
        Path child = Files.createDirectory(dir.resolve("folder"));
        Files.writeString(child.resolve("source.txt"), "needle\nlast");
        var constructor = Class.forName("io.synexia.chromellm.standalone.WorkspaceTools").getDeclaredConstructor(Path.class, boolean.class);
        constructor.setAccessible(true);
        Object tools = constructor.newInstance(dir, false);
        check(call(tools, "workspace.list", Map.of()).get("files").equals(List.of("folder/source.txt")), "portable list");
        check(((List<?>) call(tools, "workspace.search", Map.of("path", "folder", "query", "needle")).get("matches")).size() == 1, "portable search");
        check(call(tools, "workspace.read", Map.of("path", "folder/source.txt")).get("lines").equals(List.of("needle", "last")), "portable read");
        check(call(tools, "workspace.read", Map.of("path", "folder\\source.txt")).get("lines").equals(List.of("needle", "last")), "Windows relative separators");
        for (String path : List.of("folder/source.txt:private", "folder./source.txt", "folder /source.txt"))
            fails(IOException.class, () -> invoke(tool(tools, "workspace.read"), "validate", Map.of("path", path)));
        Object read = tool(tools, "workspace.read");
        Files.delete(child.resolve("source.txt"));
        // Argument admission is pure: absence of the file does not cause I/O during all-plan validation.
        invoke(read, "validate", Map.of("path", "folder/source.txt"));
        check(true, "validation performs no source I/O");
        for (String path : List.of("C:/secrets.txt", "C:\\secrets.txt", "//server/share", "folder/../source.txt"))
            fails(IOException.class, () -> call(tools, "workspace.read", Map.of("path", path)));
        Files.move(dir, root.resolve("portable-moved"));
        Files.createDirectory(dir);
        Files.writeString(dir.resolve("replacement"), "secret");
        fails(IOException.class, () -> call(tools, "workspace.list", Map.of()));
        fails(IOException.class, () -> call(tools, "workspace.read", Map.of("path", "replacement")));
    }

    private static void boundedOutput(Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("output"));
        Object tools = workspace(dir);
        var encode = Class.forName("io.synexia.chromellm.standalone.McpJson").getDeclaredMethod("encode", Object.class, int.class);
        encode.setAccessible(true);
        for (String source : List.of("x".repeat(100_000), "😀न".repeat(30_000), "\"\\\t".repeat(30_000))) {
            Files.writeString(dir.resolve("long.txt"), source);
            Map<?, ?> result = call(tools, "workspace.read", Map.of("path", "long.txt", "maxLines", 1));
            String encoded = (String) encode.invoke(null, result, 32768);
            check(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32768, "read encoded JSON byte cap");
            check(Boolean.TRUE.equals(result.get("truncated")) && Boolean.TRUE.equals(result.get("partialLine")), "long line clipping is explicit");
            String returned = (String) ((List<?>) result.get("lines")).getFirst();
            check(!returned.isEmpty() && source.startsWith(returned), "long line preserves a source prefix");
            check(!Character.isHighSurrogate(returned.charAt(returned.length() - 1)), "clipping preserves surrogate pairs");
        }
    }

    private static void directoryIterationFailure(Path root) throws Exception {
        Path selected = root.resolve("basic");
        Object tools = workspace(selected);
        Class<?> owner = tools.getClass();
        Class<?> directoryClass = Class.forName(owner.getName() + "$Directory");
        Class<?> scanClass = Class.forName(owner.getName() + "$Scan");
        var guards = owner.getDeclaredMethod("guards", Path.class);
        guards.setAccessible(true);
        var directoryConstructor = directoryClass.getDeclaredConstructor(owner, Path.class,
                java.nio.file.DirectoryStream.class, List.class);
        directoryConstructor.setAccessible(true);
        Object deadline = deadline(Duration.ofSeconds(30));
        var scanConstructor = scanClass.getDeclaredConstructor(owner, boolean.class, Map.class, deadline.getClass());
        scanConstructor.setAccessible(true);
        for (boolean failInHasNext : List.of(true, false)) {
            IOException providerFailure = new IOException("simulated directory read failure");
            boolean[] closed = {false};
            java.nio.file.DirectoryStream<Path> failing = new java.nio.file.DirectoryStream<>() {
                @Override public java.util.Iterator<Path> iterator() {
                    return new java.util.Iterator<>() {
                        @Override public boolean hasNext() {
                            if (failInHasNext) throw new java.nio.file.DirectoryIteratorException(providerFailure);
                            return true;
                        }
                        @Override public Path next() { throw new java.nio.file.DirectoryIteratorException(providerFailure); }
                    };
                }
                @Override public void close() { closed[0] = true; }
            };
            Object directory = directoryConstructor.newInstance(tools, selected, failing, guards.invoke(null, selected));
            Object scan = scanConstructor.newInstance(tools, false, Map.of(), deadline);
            try (AutoCloseable resource = (AutoCloseable) directory) {
                try {
                    invoke(scan, "walk", directory, Path.of(""), 0);
                    throw new AssertionError("directory enumeration failure returned a result");
                } catch (IOException actual) {
                    check(actual == providerFailure, "directory iteration retains the original checked provider cause");
                }
            }
            check(closed[0], "failed directory resource closes");
        }
    }

    private static Object workspace(Path root) throws Exception {
        try {
            var constructor = Class.forName("io.synexia.chromellm.standalone.WorkspaceTools").getDeclaredConstructor(Path.class);
            constructor.setAccessible(true);
            return constructor.newInstance(root);
        } catch (InvocationTargetException ex) { throwCause(ex); return null; }
    }
    private static List<?> toolList(Object tools) throws Exception { return (List<?>) invoke(tools, "tools"); }
    private static Object tool(Object tools, String name) throws Exception {
        for (Object item : toolList(tools)) if (invoke(item, "name").equals(name)) return item;
        throw new AssertionError("missing tool " + name);
    }
    private static Object deadline(Duration duration) throws Exception {
        var method = Class.forName("io.synexia.chromellm.standalone.AgentDeadline").getDeclaredMethod("start", Duration.class);
        method.setAccessible(true);
        try { return method.invoke(null, duration); } catch (InvocationTargetException ex) { throwCause(ex); return null; }
    }
    private static Map<?, ?> call(Object tools, String name, Map<String, Object> args) throws Exception {
        Object tool = tool(tools, name);
        invoke(tool, "validate", args);
        return (Map<?, ?>) invoke(tool, "execute", args, deadline(Duration.ofSeconds(30)));
    }
    private static Object invoke(Object receiver, String name, Object... args) throws Exception {
        for (var method : receiver.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                try { return method.invoke(receiver, args); } catch (InvocationTargetException ex) { throwCause(ex); }
            }
        }
        throw new NoSuchMethodException(name);
    }
    private static void throwCause(InvocationTargetException ex) throws Exception {
        if (ex.getCause() instanceof Exception cause) throw cause;
        if (ex.getCause() instanceof Error cause) throw cause;
        throw new AssertionError(ex.getCause());
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void fails(Class<? extends Exception> type, Checked action) throws Exception {
        try { action.run(); } catch (Exception ex) {
            check(type.isInstance(ex), "expected " + type.getSimpleName() + " but got " + ex);
            return;
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
