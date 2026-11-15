package io.synexia.chromellm.webmcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.HexFormat;

/** Reads only specifically allowlisted metadata files from operator-configured repositories. */
final class ApprovedInventory {
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    static final Map<String, List<String>> CONTRACT_FILES = Map.of(
        "ChromeLLM", List.of("README.md", "standalone/pom.xml", "standalone/LAZY_MODEL_CONTRACT.md", "standalone/native/CMakeLists.txt", "webmcp/README.md"),
        "dawn", List.of("README.md", "docs/quickstart-cmake.md"),
        "synexia-llmcpp", List.of("CMakeLists.txt", "ggml/src/ggml-webgpu/CMakeLists.txt"),
        "maven", List.of("README.md", "pom.xml", "docs/M3-BUILD.md", "m3-build.sh", "m3-build.ps1")
    );
    private final Properties config = new Properties();

    ApprovedInventory(Path configFile) throws IOException {
        if (!Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("configuration must be a regular existing file");
        }
        try (InputStream stream = Files.newInputStream(configFile)) { config.load(stream); }
    }

    /** Exact operator-pinned extension origin; missing entries never authorize Chrome launches. */
    String allowedOrigin() {
        return config.getProperty("agent.allowed_origin", "").trim();
    }

    String inspect(String repo) throws IOException {
        List<String> paths = CONTRACT_FILES.get(repo);
        if (paths == null) throw new IllegalArgumentException("unknown repo");
        String setting = config.getProperty("repo." + repo, "");
        if (setting.isBlank()) return "{\"ok\":false,\"error\":\"REPO_NOT_CONFIGURED\"}";
        Path root = Path.of(setting);
        if (!root.isAbsolute() || Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return "{\"ok\":false,\"error\":\"APPROVED_ROOT_UNAVAILABLE\"}";
        }
        Path ancestor = root.getRoot();
        for (Path component : root.normalize()) {
            ancestor = ancestor.resolve(component);
            if (Files.isSymbolicLink(ancestor)) {
                return "{\"ok\":false,\"error\":\"APPROVED_ROOT_UNAVAILABLE\"}";
            }
        }
        Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        StringBuilder out = new StringBuilder("{\"ok\":true,\"repo\":")
                .append(JsonStrings.quote(repo)).append(",\"files\":[");
        boolean first = true;
        for (String name : paths) {
            Path file = realRoot.resolve(name).normalize();
            if (!file.startsWith(realRoot)) throw new IOException("path escaped root");
            boolean safe = true;
            Path current = realRoot;
            for (Path segment : realRoot.relativize(file)) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) { safe = false; break; }
            }
            if (!safe || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
            long length = Files.size(file);
            if (length > MAX_FILE_BYTES) throw new IOException("contract file exceeds limit");
            MessageDigest digest = sha256();
            long observed = 0;
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    observed += n;
                    if (observed > MAX_FILE_BYTES) throw new IOException("contract file grew past limit");
                    digest.update(buf, 0, n);
                }
            }
            if (!first) out.append(',');
            first = false;
            out.append("{\"path\":").append(JsonStrings.quote(name))
                .append(",\"bytes\":").append(observed)
                .append(",\"sha256\":").append(JsonStrings.quote(HexFormat.of().formatHex(digest.digest())))
                .append('}');
        }
        return out.append("]}").toString();
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
