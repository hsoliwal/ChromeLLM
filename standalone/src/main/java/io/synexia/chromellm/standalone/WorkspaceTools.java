/*
 * Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project.
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.chromellm.standalone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** Bounded, read-only source access for an explicitly selected workspace. */
final class WorkspaceTools {
    private static final int FILE_BYTES = 1024 * 1024;
    private static final int READ_RESULT_BYTES = 32768;
    private static final long SCAN_BYTES = 16L * FILE_BYTES;
    private static final int SCAN_ENTRIES = 4096;
    private static final int SCAN_DEPTH = 64;
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final Set<OpenOption> READ_NOFOLLOW = Set.of(StandardOpenOption.READ, NOFOLLOW);
    private static final Comparator<Hit> HIT_ORDER = Comparator.comparingInt(Hit::occurrences).reversed()
            .thenComparing(Hit::path);
    private final Path root;
    private final List<Guard> rootGuards;
    private final boolean preferSecure;
    private final List<AgentTool> tools;

    WorkspaceTools(Path root) throws IOException { this(root, true); }

    // The portable path is exercised on every platform rather than only on Windows CI.
    WorkspaceTools(Path selectedRoot, boolean preferSecure) throws IOException {
        if (selectedRoot == null) throw new IOException("workspace root is required");
        Path selected = selectedRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(selected)) throw new IOException("workspace root is a symbolic link");
        root = selected.toRealPath();
        if (!Files.isDirectory(root, NOFOLLOW)) throw new IOException("workspace root is not a directory");
        rootGuards = guards(root);
        this.preferSecure = preferSecure;
        tools = List.of(new Tool("workspace.list"), new Tool("workspace.read"), new Tool("workspace.search"));
    }

    List<AgentTool> tools() { return tools; }

    private final class Tool implements AgentTool {
        private final String name;
        private Tool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() {
            return switch (name) {
                case "workspace.list" -> "List readable source paths under the workspace; reports partial traversal.";
                case "workspace.read" -> "Read a bounded UTF-8 source line range by relative path.";
                default -> "Find literal, case-sensitive UTF-16 occurrences, ranked by count then path; reports partial traversal.";
            };
        }
        @Override public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("path", Map.of("type", "string", "description", "Relative workspace path"));
            if (name.equals("workspace.read")) {
                properties.put("startLine", Map.of("type", "integer", "minimum", 1));
                properties.put("maxLines", Map.of("type", "integer", "minimum", 1, "maximum", 200, "default", 80));
            } else {
                properties.put("maxResults", Map.of("type", "integer", "minimum", 1, "maximum", 100, "default", 20));
            }
            if (name.equals("workspace.search")) properties.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 4096));
            return Map.of("type", "object", "properties", Map.copyOf(properties), "additionalProperties", false,
                    "required", name.equals("workspace.read") ? List.of("path")
                            : name.equals("workspace.search") ? List.of("query") : List.of());
        }
        @Override public void validate(Map<String, Object> arguments) throws IOException {
            if (arguments == null) throw new IOException("tool arguments are required");
            Set<String> allowed = name.equals("workspace.read") ? Set.of("path", "startLine", "maxLines")
                    : name.equals("workspace.search") ? Set.of("path", "query", "maxResults") : Set.of("path", "maxResults");
            for (String key : arguments.keySet()) if (!allowed.contains(key)) throw new IOException("unknown workspace argument");
            path(arguments, name.equals("workspace.read"));
            if (name.equals("workspace.read")) {
                integer(arguments, "startLine", 1, Integer.MAX_VALUE);
                integer(arguments, "maxLines", 80, 200);
            } else integer(arguments, "maxResults", 20, 100);
            if (name.equals("workspace.search")) {
                Object query = arguments.get("query");
                if (!(query instanceof String text) || text.isEmpty() || text.length() > 4096) throw new IOException("query must contain 1 to 4096 UTF-16 units");
            }
        }
        @Override public Object execute(Map<String, Object> arguments, AgentDeadline deadline)
                throws IOException, InterruptedException, TimeoutException {
            validate(arguments);
            deadline.check();
            Path relative = path(arguments, name.equals("workspace.read"));
            try (Directory base = openRoot()) {
                if (name.equals("workspace.read")) return read(base, relative, arguments, deadline);
                try (Directory selected = directory(base, relative, deadline)) {
                    Scan scan = new Scan(name.equals("workspace.search"), arguments, deadline);
                    scan.walk(selected, relative, 0);
                    return scan.result();
                }
            }
        }
    }

    private Map<String, Object> read(Directory base, Path relative, Map<String, Object> arguments,
                                     AgentDeadline deadline) throws IOException, InterruptedException, TimeoutException {
        Path parent = relative.getParent();
        try (Directory dir = directory(base, parent == null ? Path.of("") : parent, deadline)) {
            Source source = readSource(dir, relative.getFileName(), FILE_BYTES, deadline);
            if (source.text == null) throw new IOException("workspace source is not valid UTF-8");
            String text = source.text;
            int start = integer(arguments, "startLine", 1, Integer.MAX_VALUE);
            int limit = integer(arguments, "maxLines", 80, 200);
            List<String> lines = new ArrayList<>();
            int line = 1;
            int offset = 0;
            boolean truncated = false;
            while (offset < text.length()) {
                deadline.check();
                int end = lineEnd(text, offset);
                int next = end;
                if (next < text.length()) {
                    char separator = text.charAt(next++);
                    if (separator == '\r' && next < text.length() && text.charAt(next) == '\n') next++;
                }
                if (line >= start) {
                    if (lines.size() == limit) { truncated = true; break; }
                    lines.add(text.substring(offset, end));
                }
                offset = next;
                line++;
            }
            return boundedRead(slash(relative), start, lines, truncated, deadline);
        }
    }

    /** Reuse the transport codec as the byte oracle, including JSON escaping and UTF-8 width. */
    private static Map<String, Object> boundedRead(String path, int start, List<String> lines, boolean truncated,
                                                   AgentDeadline deadline)
            throws IOException, InterruptedException, TimeoutException {
        int chars = 0;
        for (String line : lines) chars += line.length();
        int low = 0;
        int high = Math.min(chars, READ_RESULT_BYTES);
        Map<String, Object> best = readProjection(path, start, lines, truncated, 0);
        McpJson.encode(best, READ_RESULT_BYTES);
        while (low <= high) {
            deadline.check();
            int middle = low + (high - low) / 2;
            Map<String, Object> candidate = readProjection(path, start, lines, truncated, middle);
            try {
                McpJson.encode(candidate, READ_RESULT_BYTES);
                best = candidate;
                low = middle + 1;
            } catch (IOException tooLarge) { high = middle - 1; }
        }
        deadline.check();
        return best;
    }

    private static Map<String, Object> readProjection(String path, int start, List<String> source,
                                                      boolean truncated, int characters) {
        List<String> result = new ArrayList<>();
        boolean partialLine = false;
        for (String line : source) {
            if (line.length() <= characters) {
                result.add(line);
                characters -= line.length();
            } else {
                int end = characters;
                if (end > 0 && Character.isHighSurrogate(line.charAt(end - 1))) end--;
                if (end > 0) { result.add(line.substring(0, end)); partialLine = true; }
                truncated = true;
                break;
            }
        }
        return Map.of("path", path, "startLine", start, "lines", List.copyOf(result),
                "truncated", truncated, "partialLine", partialLine);
    }

    private final class Scan {
        private final boolean searching;
        private final String query;
        private final int limit;
        private final AgentDeadline deadline;
        private final PriorityQueue<Hit> hits;
        private final PriorityQueue<String> files;
        private final Set<String> reasons = new LinkedHashSet<>();
        private final Map<String, Integer> skipReasons = new LinkedHashMap<>();
        private int skippedFiles;
        private int entries;
        private long bytes;
        private boolean stopped;
        private boolean omittedResults;
        Scan(boolean searching, Map<String, Object> arguments, AgentDeadline deadline) throws IOException {
            this.searching = searching;
            query = searching ? (String) arguments.get("query") : null;
            limit = integer(arguments, "maxResults", 20, 100);
            this.deadline = deadline;
            hits = new PriorityQueue<>(limit, HIT_ORDER.reversed());
            files = new PriorityQueue<>(limit, Comparator.reverseOrder());
        }
        void walk(Directory dir, Path relative, int depth) throws IOException, InterruptedException, TimeoutException {
            dir.verify();
            try {
                for (Path entry : dir.stream) {
                    deadline.check();
                    if (stopped) break;
                    if (entries == SCAN_ENTRIES) { stop("entries"); break; }
                    entries++;
                    Path name = entry.getFileName();
                    if (denied(name.toString())) continue;
                    BasicFileAttributes attributes = dir.attributes(name);
                    if (attributes.isSymbolicLink()) continue;
                    Path child = relative.resolve(name.toString());
                    if (attributes.isDirectory()) {
                        if (depth >= SCAN_DEPTH) { reasons.add("depth"); continue; }
                        try (Directory nested = dir.child(name)) { walk(nested, child, depth + 1); }
                    } else if (attributes.isRegularFile()) {
                        long size = attributes.size();
                        if (searching && size > FILE_BYTES) { skip("fileBytes"); continue; }
                        if (size > SCAN_BYTES - bytes) { stop("bytes"); break; }
                        if (searching) {
                            // Actual bytes, including any observed growth, are independently bounded.
                            Source source = readSource(dir, name, (int) Math.min(FILE_BYTES, SCAN_BYTES - bytes), deadline);
                            bytes += source.bytes;
                            if (source.text == null) { skip("invalidUtf8"); continue; }
                            if (source.text.indexOf('\0') >= 0) { skip("binary"); continue; }
                            Hit hit = match(slash(child), source.text, query, deadline);
                            if (hit != null) retain(hit);
                        } else {
                            bytes += size;
                            retain(slash(child));
                        }
                    }
                }
            } catch (DirectoryIteratorException failedIteration) {
                throw failedIteration.getCause();
            }
            dir.verify();
        }
        private void stop(String reason) { reasons.add(reason); stopped = true; }
        private void skip(String reason) {
            skippedFiles++;
            reasons.add(reason);
            skipReasons.merge(reason, 1, Integer::sum);
        }
        private void retain(Hit hit) {
            if (hits.size() < limit) hits.add(hit);
            else {
                omittedResults = true;
                if (HIT_ORDER.compare(hit, hits.peek()) < 0) { hits.remove(); hits.add(hit); }
            }
        }
        private void retain(String path) {
            if (files.size() < limit) files.add(path);
            else {
                omittedResults = true;
                if (path.compareTo(files.peek()) < 0) { files.remove(); files.add(path); }
            }
        }
        Map<String, Object> result() {
            Map<String, Object> output = new LinkedHashMap<>();
            if (searching) output.put("matches", hits.stream().sorted(HIT_ORDER).map(Hit::asMap).toList());
            else output.put("files", files.stream().sorted().toList());
            // The result cap and traversal caps are distinct: a top-k is complete even when more matches exist.
            output.put("truncated", !reasons.isEmpty() || omittedResults);
            output.put("scanComplete", reasons.isEmpty());
            output.put("truncationReasons", reasons.isEmpty() && omittedResults ? List.of("results")
                    : omittedResults ? withResults(reasons) : List.copyOf(reasons));
            output.put("skippedFiles", skippedFiles);
            output.put("skipReasons", Map.copyOf(skipReasons));
            output.put("scannedEntries", entries);
            output.put("scannedBytes", bytes);
            return output;
        }
    }

    private static List<String> withResults(Set<String> reasons) {
        List<String> result = new ArrayList<>(reasons);
        result.add("results");
        return List.copyOf(result);
    }

    private record Hit(String path, int occurrences, int firstOffset, int firstLine, String excerpt) {
        Map<String, Object> asMap() { return Map.of("path", path, "occurrences", occurrences,
                "firstOffset", firstOffset, "firstLine", firstLine, "excerpt", excerpt); }
    }

    /** Uses the JDK UTF-16 owner. The file and query caps bound its non-linear worst case. */
    private static Hit match(String path, String text, String query, AgentDeadline deadline)
            throws InterruptedException, TimeoutException {
        int count = 0;
        int first = -1;
        int from = 0;
        while (from <= text.length() - query.length()) {
            deadline.check();
            int found = text.indexOf(query, from);
            deadline.check();
            if (found < 0) break;
            if (first < 0) first = found;
            count++;
            from = found + 1;
        }
        if (first < 0) return null;
        int line = 1;
        for (int i = 0; i < first; i++) {
            if ((i & 4095) == 0) deadline.check();
            if (text.charAt(i) == '\n' || text.charAt(i) == '\r'
                    && (i + 1 >= text.length() || text.charAt(i + 1) != '\n')) line++;
        }
        int begin = Math.max(0, first - 40);
        int end = Math.min(text.length(), begin + 160);
        if (begin > 0 && Character.isLowSurrogate(text.charAt(begin))) begin--;
        if (end < text.length() && end > begin && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return new Hit(path, count, first, line, text.substring(begin, end));
    }

    private static int lineEnd(String text, int offset) {
        int cursor = offset;
        while (cursor < text.length() && text.charAt(cursor) != '\r' && text.charAt(cursor) != '\n') cursor++;
        return cursor;
    }

    private static int integer(Map<String, Object> arguments, String name, int fallback, int max) throws IOException {
        if (!arguments.containsKey(name)) return fallback;
        Object value = arguments.get(name);
        long number;
        if (value instanceof BigDecimal decimal) {
            try { number = decimal.intValueExact(); }
            catch (ArithmeticException invalid) { throw new IOException(name + " must be an exact bounded integer", invalid); }
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            number = ((Number) value).longValue();
        } else throw new IOException(name + " must be an integer");
        if (number < 1 || number > max) throw new IOException(name + " is outside its bounded range");
        return (int) number;
    }

    private static Path path(Map<String, Object> arguments, boolean required) throws IOException {
        Object value = arguments.getOrDefault("path", "");
        if (!(value instanceof String text) || text.length() > 4096 || required && text.isEmpty()) throw new IOException("invalid relative workspace path");
        try {
            Path relative = Path.of(text.replace('\\', '/'));
            // Reject drive/UNC forms even on Unix, where they otherwise look relative.
            if (relative.isAbsolute() || relative.getRoot() != null || text.startsWith("/")
                    || text.startsWith("\\") || text.indexOf(':') >= 0)
                throw new IOException("workspace path must be relative without a drive or stream");
            for (Path component : relative) {
                String name = component.toString();
                if (name.equals("..") || name.endsWith(" ") || !name.equals(".") && name.endsWith(".") || denied(name)) throw new IOException("workspace path is denied");
            }
            relative = relative.normalize();
            if (required && relative.toString().isEmpty()) throw new IOException("source file path is required");
            return relative;
        } catch (InvalidPathException invalid) { throw new IOException("invalid workspace path", invalid); }
    }

    private static boolean denied(String name) {
        String folded = name.toLowerCase(Locale.ROOT);
        return Set.of(".git", "target", "node_modules", ".env", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519").contains(folded)
                || folded.startsWith(".env.") || folded.endsWith(".pem") || folded.endsWith(".key")
                || folded.endsWith(".p12") || folded.endsWith(".pfx") || folded.endsWith(".pkcs12");
    }

    private Directory openRoot() throws IOException {
        verifyGuards(rootGuards);
        DirectoryStream<Path> stream = Files.newDirectoryStream(root);
        try {
            Directory directory = new Directory(root, stream, guards(root));
            directory.verify();
            if (directory.secure != null) {
                BasicFileAttributes opened = directory.secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
                same(rootGuards.getLast().attributes, opened, false);
            }
            return directory;
        } catch (IOException | RuntimeException | Error failure) {
            try { stream.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private Directory directory(Directory base, Path relative, AgentDeadline deadline)
            throws IOException, InterruptedException, TimeoutException {
        if (relative.toString().isEmpty()) return base.borrow();
        Directory cursor = base.borrow();
        try {
            for (Path component : relative) {
                deadline.check();
                Directory next = cursor.child(component);
                cursor.close();
                cursor = next;
            }
            return cursor;
        } catch (IOException | InterruptedException | TimeoutException | RuntimeException | Error failure) {
            try { cursor.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private final class Directory implements AutoCloseable {
        private final Path absolute;
        private final DirectoryStream<Path> stream;
        private final SecureDirectoryStream<Path> secure;
        private final List<Guard> identities;
        private final boolean owned;
        @SuppressWarnings("unchecked")
        Directory(Path absolute, DirectoryStream<Path> stream, List<Guard> identities) {
            this(absolute, stream, preferSecure && stream instanceof SecureDirectoryStream<?> ? (SecureDirectoryStream<Path>) stream : null, identities, true);
        }
        private Directory(Path absolute, DirectoryStream<Path> stream, SecureDirectoryStream<Path> secure, List<Guard> identities, boolean owned) {
            this.absolute = absolute;
            this.stream = stream;
            this.secure = secure;
            this.identities = identities;
            this.owned = owned;
        }
        Directory borrow() { return new Directory(absolute, stream, secure, identities, false); }
        void verify() throws IOException { verifyGuards(rootGuards); verifyGuards(identities); }
        BasicFileAttributes attributes(Path name) throws IOException {
            verify();
            BasicFileAttributes attributes = secure != null
                    ? secure.getFileAttributeView(name, BasicFileAttributeView.class, NOFOLLOW).readAttributes()
                    : Files.readAttributes(absolute.resolve(name), BasicFileAttributes.class, NOFOLLOW);
            verify();
            return attributes;
        }
        Directory child(Path name) throws IOException {
            BasicFileAttributes before = attributes(name);
            if (!before.isDirectory() || before.isSymbolicLink()) throw new IOException("workspace path is not a regular directory");
            DirectoryStream<Path> child = secure != null ? secure.newDirectoryStream(name, NOFOLLOW) : Files.newDirectoryStream(absolute.resolve(name));
            try {
                Directory result = new Directory(absolute.resolve(name), child, guards(absolute.resolve(name)));
                if (result.secure != null) same(before, result.secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes(), false);
                else same(before, Files.readAttributes(result.absolute, BasicFileAttributes.class, NOFOLLOW), false);
                result.verify();
                return result;
            } catch (IOException | RuntimeException | Error failure) {
                try { child.close(); } catch (IOException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }
        SeekableByteChannel open(Path name) throws IOException {
            verify();
            SeekableByteChannel channel = secure != null ? secure.newByteChannel(name, READ_NOFOLLOW)
                    : Files.newByteChannel(absolute.resolve(name), READ_NOFOLLOW);
            try { verify(); return channel; }
            catch (IOException | RuntimeException | Error failure) {
                try { channel.close(); } catch (IOException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }
        @Override public void close() throws IOException { if (owned) stream.close(); }
    }

    private record Source(String text, int bytes) { }

    private Source readSource(Directory directory, Path name, int maxBytes, AgentDeadline deadline)
            throws IOException, InterruptedException, TimeoutException {
        deadline.check();
        BasicFileAttributes before = directory.attributes(name);
        if (!before.isRegularFile() || before.isSymbolicLink()) throw new IOException("workspace input is not a regular file");
        if (before.size() > maxBytes) throw new IOException("workspace source exceeds the byte limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(before.size(), 8192));
        try (SeekableByteChannel input = directory.open(name)) {
            ByteBuffer block = ByteBuffer.allocate(8192);
            int read;
            while (true) {
                deadline.check();
                block.clear();
                block.limit(Math.min(block.capacity(), maxBytes - output.size() + 1));
                read = input.read(block);
                if (read < 0) break;
                if (read > maxBytes - output.size()) throw new IOException("workspace source exceeds the byte limit");
                output.write(block.array(), 0, read);
            }
            same(before, directory.attributes(name), true);
            directory.verify();
        }
        deadline.check();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
            return new Source(text, output.size());
        } catch (CharacterCodingException invalid) { return new Source(null, output.size()); }
    }

    private record Guard(Path path, BasicFileAttributes attributes) { }
    private static List<Guard> guards(Path absolute) throws IOException {
        List<Guard> result = new ArrayList<>();
        Path cursor = absolute.getRoot();
        result.add(new Guard(cursor, directoryAttributes(cursor)));
        for (Path component : absolute) {
            cursor = cursor.resolve(component);
            result.add(new Guard(cursor, directoryAttributes(cursor)));
        }
        return List.copyOf(result);
    }
    private static BasicFileAttributes directoryAttributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) throw new IOException("workspace directory changed or is a symbolic link");
        return attributes;
    }
    private static void verifyGuards(List<Guard> identities) throws IOException {
        for (Guard guard : identities) same(guard.attributes, directoryAttributes(guard.path), false);
    }
    private static void same(BasicFileAttributes before, BasicFileAttributes after, boolean content) throws IOException {
        if (before.isDirectory() != after.isDirectory() || before.isRegularFile() != after.isRegularFile()
                || after.isSymbolicLink() || !Objects.equals(before.fileKey(), after.fileKey())
                || !before.creationTime().equals(after.creationTime())
                || content && (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())))
            throw new IOException("workspace input changed during access");
    }
    private static String slash(Path path) { return path.toString().replace('\\', '/'); }
}
