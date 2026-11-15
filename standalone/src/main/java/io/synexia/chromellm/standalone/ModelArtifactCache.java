// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Opt-in, checksum-pinned, bounded GGUF artifact acquisition; no implicit cache directories. */
final class ModelArtifactCache {
    static final long MAX_BYTES = 64L * 1024 * 1024 * 1024;

    @FunctionalInterface
    interface Downloader {
        InputStream open(URI uri) throws IOException, InterruptedException;
    }

    private final URI url;
    private final String sha256;
    private final Path directory;
    private final Downloader downloader;
    private final long maxBytes;

    ModelArtifactCache(String url, String sha256, Path directory) {
        this(url, sha256, directory, ModelArtifactCache::fetch, MAX_BYTES);
    }

    /** Package-private test seam; tests never download real model data. */
    ModelArtifactCache(String rawUrl, String rawSha256, Path cache, Downloader downloader, long maxBytes) {
        Objects.requireNonNull(rawUrl, "url");
        this.url = URI.create(rawUrl);
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                || url.getHost().isBlank() || url.getRawUserInfo() != null
                || url.getRawFragment() != null || url.getPort() == 0) {
            throw new IllegalArgumentException("model URL must be an HTTPS URL without credentials or fragment");
        }
        if (rawSha256 == null || !rawSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("model SHA-256 must contain 64 hex digits");
        }
        this.sha256 = rawSha256.toLowerCase(Locale.ROOT);
        this.directory = Objects.requireNonNull(cache, "cache directory").toAbsolutePath().normalize();
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        if (maxBytes < 1 || maxBytes > MAX_BYTES) {
            throw new IllegalArgumentException("invalid artifact byte bound");
        }
        this.maxBytes = maxBytes;
    }

    Path resolve() throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("model acquisition interrupted");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(directory)) {
            throw new IOException("model cache directory must exist and be writable");
        }
        Path target = directory.resolve(sha256 + ".gguf");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verify(target);
            return target;
        }
        Path stage = Files.createTempFile(directory, ".chromellm-model-", ".part");
        try {
            MessageDigest digest = digest();
            long count = 0;
            try (InputStream source = downloader.open(url);
                    OutputStream output = Files.newOutputStream(stage,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = source.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("model acquisition interrupted");
                    }
                    if (read > maxBytes - count) throw new IOException("model artifact exceeds byte bound");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    count += read;
                }
            }
            if (count == 0 || !MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(sha256))) {
                throw new IOException("model artifact SHA-256 mismatch or empty download");
            }
            // An atomic hard-link publication cannot replace a simultaneously created cache entry.
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                verify(target);
                return target;
            }
            try {
                Files.createLink(target, stage);
            } catch (FileAlreadyExistsException concurrent) {
                verify(target);
                return target;
            }
            verify(target);
            return target;
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    private void verify(Path target) throws IOException, InterruptedException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("cached model is not a regular file");
        }
        MessageDigest digest = digest();
        long size = 0;
        try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("cache check interrupted");
                if (read > maxBytes - size) throw new IOException("cached model exceeds byte bound");
                digest.update(buffer, 0, read);
                size += read;
            }
        }
        if (size == 0 || !MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(sha256))) {
            throw new IOException("cached model SHA-256 mismatch");
        }
    }

    private static InputStream fetch(URI url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofHours(1))
                .header("Accept-Encoding", "identity").GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        InputStream input = response.body();
        if (response.statusCode() != 200) {
            input.close();
            throw new IOException("model download returned HTTP " + response.statusCode());
        }
        String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        if (!"identity".equalsIgnoreCase(encoding)
                || response.headers().firstValueAsLong("Content-Length").orElse(-1) > MAX_BYTES) {
            input.close();
            throw new IOException("model artifact has unsupported encoding or size");
        }
        return input;
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossibleOnJava21) {
            throw new IllegalStateException("SHA-256 unavailable", impossibleOnJava21);
        }
    }
}
