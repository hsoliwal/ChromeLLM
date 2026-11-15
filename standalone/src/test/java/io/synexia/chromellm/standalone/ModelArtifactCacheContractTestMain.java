// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline cache and first-inference contracts; no licensed model or network needed. */
public final class ModelArtifactCacheContractTestMain {
    private static int checks;
    private ModelArtifactCacheContractTestMain() { }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("chromellm-model-cache-");
        byte[] fixture = "synthetic non-model fixture".getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture));
        String url = "https://example.invalid/model.gguf";
        AtomicInteger opens = new AtomicInteger();
        ModelArtifactCache.Downloader download = ignored -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(fixture);
        };
        try {
            reject(() -> new ModelArtifactCache("http://example.invalid/model.gguf", hash, dir, download, 100));
            reject(() -> new ModelArtifactCache("https://u:p@example.invalid/m", hash, dir, download, 100));
            reject(() -> new ModelArtifactCache("https://example.invalid/m#fragment", hash, dir, download, 100));
            reject(() -> new ModelArtifactCache(url, "bad", dir, download, 100));
            reject(() -> new ModelArtifactCache(url, hash, dir, download, 0));
            ModelArtifactCache cache = new ModelArtifactCache(url, hash.toUpperCase(), dir, download, 100);
            check(opens.get() == 0, "eager model download");
            Path file = cache.resolve();
            check(file.getFileName().toString().equals(hash + ".gguf"), "content-address mismatch");
            check(Files.readAllBytes(file).length == fixture.length, "published content mismatch");
            check(opens.get() == 1, "one cache miss expected");
            check(cache.resolve().equals(file) && opens.get() == 1, "cache hit downloaded again");
            Files.writeString(file, "corrupt");
            rejectIo(cache::resolve);
            check(opens.get() == 1, "corrupt cache silently repaired");
            Files.delete(file);
            ModelArtifactCache wrong = new ModelArtifactCache(url, "a".repeat(64), dir, download, 100);
            rejectIo(wrong::resolve);
            check(!Files.exists(dir.resolve("a".repeat(64) + ".gguf")), "mismatched bytes published");
            ModelArtifactCache capped = new ModelArtifactCache(url, hash, dir, download, 3);
            rejectIo(capped::resolve);
            check(!Files.exists(file), "oversize bytes published");
            try (var files = Files.list(dir)) {
                check(files.findAny().isEmpty(), "staging file remained after failure");
            }
            AtomicInteger initialized = new AtomicInteger();
            AtomicInteger inferred = new AtomicInteger();
            LazyModelBackend lazy = new LazyModelBackend("llama.cpp-cli", cache, model -> {
                check(Files.isRegularFile(model), "provider opened before acquisition");
                initialized.incrementAndGet();
                return new ModelBackend() {
                    public String id() { return "llama.cpp-cli"; }
                    public ModelResponse infer(ModelRequest request) {
                        inferred.incrementAndGet();
                        return new ModelResponse("synthetic", id(), "0".repeat(64));
                    }
                };
            });
            check(lazy.id().equals("llama.cpp-cli") && initialized.get() == 0, "lazy construction opened provider");
            ModelRequest request = new ModelRequest("test", 8, java.time.Duration.ofSeconds(10));
            lazy.infer(request);
            lazy.infer(request);
            check(initialized.get() == 1 && inferred.get() == 2, "provider reopened after first inference");
            lazy.close();
            rejectIo(() -> lazy.infer(request));
            check(opens.get() == 4, "unexpected fixture fetch count");
        } finally {
            try (var all = Files.walk(dir)) {
                for (Path pathEntry : all.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(pathEntry);
                }
            }
        }
        System.out.println("lazy model artifact contract checks passed: " + checks);
        ModelAcquisitionLifecycleTestMain.main(new String[0]);
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void reject(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("invalid artifact configuration accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void rejectIo(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("unsafe cache state accepted"); }
        catch (IOException expected) { checks++; }
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
