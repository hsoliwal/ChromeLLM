// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Opt-in integration: a real packaged JNI artifact, checksum-pinned model, and the existing smart-build owner. */
public final class SmartBuildModelIntegrationTestMain {
    private SmartBuildModelIntegrationTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5 || !args[3].matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("pass main JAR, real native JAR, HTTPS model URL, SHA-256, and optional preverified cache seed");
        }
        Path root = Files.createTempDirectory("chromellm real smart build ");
        Throwable primary = null;
        try {
            Path main = Files.copy(Path.of(args[0]), root.resolve("chromellm-main.jar"));
            Path nativeJar = Files.copy(Path.of(args[1]), root.resolve("chromellm-natives.jar"));
            Path target = Files.createDirectory(root.resolve("target"));
            String prompt = "Once upon a time";
            Files.writeString(root.resolve("prompt.txt"), prompt);
            Path cache = target.resolve("model-cache");
            if (args.length == 5) {
                Path seed = Path.of(args[4]);
                if (!hash(seed).equals(args[3])) throw new IOException("integration cache seed SHA-256 mismatch");
                Files.createDirectory(cache);
                Files.copy(seed, cache.resolve(args[3] + ".gguf"));
            }
            String recipe = "promptFile=prompt.txt\nbackend=jni\nmodelUrl=" + property(args[2])
                    + "\nmodelSha256=" + args[3] + "\nmodelCacheDir=target/model-cache\n"
                    + "modelFollowRedirects=true\nmodelDownloadTimeoutSeconds=120\nmodelMaxBytes=2097152\n"
                    + "computeBackend=cpu\ngpuLayers=0\ncontextTokens=512\nthreads=2\nmaxTokens=8\ntimeoutSeconds=60\n";
            Path recipeFile = root.resolve("recipe.properties");
            Files.writeString(recipeFile, recipe);
            run(root, main, nativeJar, recipeFile, "first");
            Path model = cache.resolve(args[3] + ".gguf");
            if (!hash(model).equals(args[3])) throw new AssertionError("actual model cache digest mismatch");
            long modified = Files.getLastModifiedTime(model).toMillis();
            List<Path> first = candidates(target);
            if (first.size() != 1) throw new AssertionError("smart build must publish one candidate");
            verifyReceipt(first.getFirst(), prompt, recipeFile);
            String candidate = Files.readString(first.getFirst());
            if (candidate.isBlank()) throw new AssertionError("real native model returned no candidate");
            // The same digest must resolve locally even when the source endpoint is unavailable.
            // This is a distinct recipe, so the original result and receipt remain immutable.
            Path cachedRecipe = root.resolve("cached-recipe.properties");
            Files.writeString(cachedRecipe, recipe.replace(property(args[2]), "https://offline.invalid/no-download.gguf"));
            run(root, main, nativeJar, cachedRecipe, "cached");
            List<Path> repeated = candidates(target);
            if (repeated.size() != 2) throw new AssertionError("cache replay lost an immutable smart-build result");
            Path second = repeated.stream().filter(path -> !path.equals(first.getFirst())).findFirst().orElseThrow();
            verifyReceipt(second, prompt, cachedRecipe);
            if (!Files.readString(second).equals(candidate) || Files.getLastModifiedTime(model).toMillis() != modified) {
                throw new AssertionError("verified model cache or deterministic inference changed on reuse");
            }
            System.out.println("real packaged JNI smart-build integration passed: model digest, candidate/recipe/prompt receipts, "
                    + "CPU inference, immutable results and offline cache reuse; download="
                    + (args.length == 5 ? "NOT_EXECUTED_PREVERIFIED_CACHE_SEED" : "HTTPS_EXECUTED")
                    + "; model.sha256=" + args[3] + "; candidate.sha256=" + hash(candidate.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            IOException cleanupFailure = null;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    try { Files.deleteIfExists(path); }
                    catch (IOException cleanup) {
                        if (cleanupFailure == null) cleanupFailure = cleanup;
                        else cleanupFailure.addSuppressed(cleanup);
                    }
                }
            }
            if (cleanupFailure != null) {
                if (primary == null) throw cleanupFailure;
                primary.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void run(Path root, Path main, Path nativeJar, Path recipe, String run) throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        List<String> command = new ArrayList<>(List.of(javaExecutable.toString(), "-Xcheck:jni"));
        if (run.equals("cached")) command.add("-Dchromellm.offline=true");
        // Preserve an explicitly configured JVM transport for CI/corporate networks without
        // logging its settings or introducing a second HTTP client/downloader.
        for (String key : List.of("https.proxyHost", "https.proxyPort", "http.nonProxyHosts",
                "javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType")) {
            String value = System.getProperty(key);
            if (value != null) command.add("-D" + key + "=" + value);
        }
        command.addAll(List.of("-Dchromellm.native.cache=" + root.resolve("native-cache"), "--class-path",
                main + java.io.File.pathSeparator + nativeJar, "io.synexia.chromellm.standalone.SmartBuildMain",
                recipe.toString(), root.toString(), root.resolve("target").toString()));
        Path log = root.resolve(run + ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        for (String variable : List.of("LD_LIBRARY_PATH", "LD_PRELOAD", "DYLD_LIBRARY_PATH",
                "DYLD_FALLBACK_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES")) builder.environment().remove(variable);
        Process child = builder.start();
        try {
            if (!child.waitFor(Duration.ofSeconds(240).toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("real smart-build integration exceeded its deadline");
            }
            String diagnostic;
            try (var input = Files.newInputStream(log)) {
                diagnostic = new String(input.readNBytes(65536), StandardCharsets.UTF_8);
            }
            if (child.exitValue() != 0) throw new AssertionError("real smart-build child failed: " + diagnostic);
            if (diagnostic.lines().filter(line -> line.startsWith("ChromeLLM smart-build candidate SHA-256:")).count() != 1) {
                throw new AssertionError("SmartBuildMain must execute its selected online/offline recipe exactly once");
            }
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static List<Path> candidates(Path target) throws IOException {
        try (var entries = Files.list(target.resolve("smart-build"))) {
            return entries.filter(path -> path.getFileName().toString().startsWith("candidate-")).sorted().toList();
        }
    }

    private static void verifyReceipt(Path candidate, String prompt, Path recipe) throws Exception {
        String name = candidate.getFileName().toString().replace("candidate-", "receipt-").replace(".txt", ".properties");
        Properties receipt = new Properties();
        receipt.load(new StringReader(Files.readString(candidate.resolveSibling(name))));
        if (!"llama.cpp-jni".equals(receipt.getProperty("backend"))
                || !hash(candidate).equals(receipt.getProperty("candidate.sha256"))
                || !hash(recipe).equals(receipt.getProperty("recipe.sha256"))
                || !hash(prompt.getBytes(StandardCharsets.UTF_8)).equals(receipt.getProperty("prompt.sha256"))) {
            throw new AssertionError("smart-build receipt does not bind actual inputs and output");
        }
    }

    private static String property(String text) {
        return text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
