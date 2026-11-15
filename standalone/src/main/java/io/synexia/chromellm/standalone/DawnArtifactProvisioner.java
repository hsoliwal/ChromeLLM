// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Maven-only, checksum-pinned Dawn Release install archive provisioner. Never launches Chrome. */
public final class DawnArtifactProvisioner {
    private static final long MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;
    private static final Set<String> CDN_HOSTS = Set.of("release-assets.githubusercontent.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com");

    @FunctionalInterface
    interface Fetcher {
        FetchResponse open(URI uri) throws IOException, InterruptedException;
    }
    record FetchResponse(int status, String redirect, String encoding, long contentLength,
                         InputStream body) implements AutoCloseable {
        @Override public void close() throws IOException { body.close(); }
    }

    private DawnArtifactProvisioner() { }

    /** Arguments deliberately do not accept an output path outside the project target directory. */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(
                "expected Dawn URL, SHA-256, Maven project root, Maven target path");
        provision(args[0], args[1], Path.of(args[2]), Path.of(args[3]), DawnArtifactProvisioner::fetch);
    }

    static Path provision(String rawUrl, String sha256, Path project, Path target, Fetcher downloader)
            throws Exception {
        URI url = releaseUrl(rawUrl);
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Dawn release requires a 64-hex SHA-256 pin");
        }
        String expected = sha256.toLowerCase(Locale.ROOT);
        Path root = project.toRealPath();
        Path targetPath = target.toAbsolutePath().normalize();
        if (!targetPath.equals(root.resolve("target")) || !Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS)
                || !targetPath.toRealPath().equals(targetPath)) {
            throw new IOException("Dawn artifacts must use the existing Maven project target directory");
        }
        Path workspace = targetPath.resolve("native-webgpu-assets");
        Files.createDirectories(workspace);
        if (Files.isSymbolicLink(workspace) || !workspace.toRealPath().equals(workspace)) {
            throw new IOException("Dawn workspace is not an ordinary target directory");
        }
        Path archive = workspace.resolve("dawn-" + expected + ".tar.gz");
        if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            checkDigest(archive, expected);
        } else {
            Path staging = Files.createTempFile(workspace, ".dawn-download-", ".tmp");
            try {
                download(url, staging, expected, Objects.requireNonNull(downloader, "downloader"));
                try { Files.createLink(archive, staging); }
                catch (FileAlreadyExistsException collision) { checkDigest(archive, expected); }
            } finally { Files.deleteIfExists(staging); }
        }
        Path install = workspace.resolve("dawn");
        Path extraction = Files.createTempDirectory(workspace, ".dawn-extract-");
        try {
            extract(archive, extraction);
            if (!hasDawnConfig(extraction)) throw new IOException("Dawn archive lacks DawnConfig.cmake");
            if (Files.exists(install, LinkOption.NOFOLLOW_LINKS)) {
                verifySameTree(extraction, install);
            } else {
                try { Files.move(extraction, install, StandardCopyOption.ATOMIC_MOVE); }
                catch (FileAlreadyExistsException collision) { verifySameTree(extraction, install); }
            }
        } finally { deleteTree(extraction); }
        System.out.println("verified Dawn archive SHA-256 " + expected);
        return install;
    }

    private static URI releaseUrl(String raw) {
        if (raw == null) throw new IllegalArgumentException("Dawn release URL missing");
        URI uri;
        try { uri = URI.create(raw); }
        catch (IllegalArgumentException malformed) { throw new IllegalArgumentException("invalid Dawn release URL", malformed); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1
                || uri.getRawQuery() != null || !uri.getPath().matches(
                    "/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[^/]+/[^/]+\\.(?:tar\\.gz|tgz)")) {
            throw new IllegalArgumentException("Dawn archive must be a pinned HTTPS GitHub release tar.gz URL");
        }
        return uri;
    }

    private static URI redirect(URI from, String location) throws IOException {
        if (location == null || location.isBlank()) throw new IOException("missing release redirect");
        URI next = from.resolve(location);
        String host = next.getHost() == null ? "" : next.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(next.getScheme()) || !CDN_HOSTS.contains(host)
                || next.getUserInfo() != null || next.getFragment() != null || next.getPort() != -1) {
            throw new IOException("Dawn release redirected to an unapproved host");
        }
        return next;
    }

    private static FetchResponse fetch(URI uri) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30))
                .header("Accept-Encoding", "identity").GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        return new FetchResponse(response.statusCode(), response.headers().firstValue("Location").orElse(null),
                response.headers().firstValue("Content-Encoding").orElse("identity"),
                response.headers().firstValueAsLong("Content-Length").orElse(-1), response.body());
    }

    private static void download(URI url, Path stage, String expected, Fetcher fetcher) throws Exception {
        URI current = url;
        for (int step = 0; step < 5; step++) {
            try (FetchResponse response = fetcher.open(current)) {
                if (response.status >= 300 && response.status < 400) {
                    if (step == 4) throw new IOException("too many Dawn release redirects");
                    current = redirect(current, response.redirect);
                    continue;
                }
                if (response.status != 200 || !"identity".equalsIgnoreCase(response.encoding)
                        || response.contentLength > MAX_ARCHIVE_BYTES) {
                    throw new IOException("invalid Dawn artifact HTTP response");
                }
                MessageDigest digest = digest();
                long count = 0;
                try (OutputStream out = Files.newOutputStream(stage, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] bytes = new byte[64 * 1024];
                    int n;
                    while ((n = response.body.read(bytes)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("download interrupted");
                        if (n > MAX_ARCHIVE_BYTES - count) throw new IOException("Dawn artifact exceeds byte bound");
                        count += n;
                        digest.update(bytes, 0, n);
                        out.write(bytes, 0, n);
                    }
                }
                if (count == 0 || (response.contentLength >= 0 && count != response.contentLength)
                        || !MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(expected))) {
                    throw new IOException("Dawn artifact is incomplete or SHA-256 mismatched");
                }
                return;
            }
        }
        throw new IOException("unreachable Dawn redirect limit");
    }

    private static void checkDigest(Path file, String expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("invalid cached Dawn archive");
        MessageDigest digest = digest();
        long count = 0;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n > MAX_ARCHIVE_BYTES - count) throw new IOException("cached Dawn archive oversized");
                count += n;
                digest.update(buffer, 0, n);
            }
        }
        if (count == 0 || !MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(expected))) {
            throw new IOException("cached Dawn archive SHA-256 mismatch");
        }
    }

    private static void extract(Path archive, Path outputRoot) throws IOException {
        String rootName = null;
        String extendedName = null;
        int entries = 0;
        long total = 0;
        try (InputStream source = new GZIPInputStream(Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS))) {
            byte[] header = new byte[512];
            while (true) {
                readFully(source, header, header.length);
                boolean zeros = true;
                for (byte b : header) if (b != 0) { zeros = false; break; }
                if (zeros) break;
                if (++entries > MAX_ENTRIES) throw new IOException("Dawn tar contains too many entries");
                String name = tarText(header, 0, 100);
                String prefix = tarText(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = octal(header, 124, 12);
                if (size > MAX_EXTRACTED_BYTES - total) throw new IOException("Dawn tar exceeds extraction bound");
                byte type = header[156];
                if (type == 'x' || type == 'g' || type == 'L') {
                    if (size > 1024 * 1024) throw new IOException("excessive Dawn tar metadata");
                    byte[] raw = new byte[(int) size];
                    readFully(source, raw, raw.length);
                    if (type == 'x') extendedName = paxPath(raw);
                    else if (type == 'L') extendedName = new String(raw, java.nio.charset.StandardCharsets.UTF_8).replace("\u0000", "").strip();
                    total += size;
                    skipPadding(source, size);
                    continue;
                }
                if (type != '0' && type != 0 && type != '5') throw new IOException("Dawn tar contains a non-file/link entry");
                if (extendedName != null) { name = extendedName; extendedName = null; }
                String[] segments = name.replaceAll("/+$", "").split("/", -1);
                if (segments.length == 0 || !segments[0].startsWith("Dawn-") || segments[0].equals("Dawn-")
                        || Arrays.stream(segments).anyMatch(s -> s.isBlank() || s.equals(".") || s.equals("..") || s.contains("\\") || s.contains(":"))) {
                    throw new IOException("Dawn tar entry has invalid path");
                }
                if (rootName == null) rootName = segments[0];
                if (!rootName.equals(segments[0])) throw new IOException("Dawn tar contains mixed archive roots");
                Path local = outputRoot;
                for (int i = 1; i < segments.length; i++) local = local.resolve(segments[i]);
                if (!local.normalize().startsWith(outputRoot) || Files.isSymbolicLink(local)) {
                    throw new IOException("Dawn tar entry escapes extraction root");
                }
                if (type == '5') {
                    if (size != 0) throw new IOException("Dawn tar directory has nonzero size");
                    Files.createDirectories(local);
                } else {
                    if (segments.length == 1 || Files.exists(local, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Dawn tar contains duplicate or root-level file");
                    }
                    Files.createDirectories(local.getParent());
                    try (OutputStream out = Files.newOutputStream(local, StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[64 * 1024];
                        long remaining = size;
                        while (remaining > 0) {
                            int n = source.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                            if (n < 0) throw new IOException("truncated Dawn tar payload");
                            if (n == 0) continue;
                            out.write(buffer, 0, n);
                            remaining -= n;
                        }
                    }
                    total += size;
                }
                skipPadding(source, size);
            }
        }
    }

    private static String paxPath(byte[] bytes) throws IOException {
        // PAX length-prefixed records, e.g. "25 path=Dawn-package/file\n".
        int cursor = 0;
        String found = null;
        while (cursor < bytes.length) {
            int space = cursor;
            while (space < bytes.length && bytes[space] >= '0' && bytes[space] <= '9') space++;
            if (space == cursor || space >= bytes.length || bytes[space] != ' ') throw new IOException("invalid Dawn PAX header");
            int length;
            try { length = Integer.parseInt(new String(bytes, cursor, space - cursor, java.nio.charset.StandardCharsets.US_ASCII)); }
            catch (NumberFormatException error) { throw new IOException("invalid Dawn PAX record length", error); }
            if (length <= space - cursor + 1 || length > bytes.length - cursor || bytes[cursor + length - 1] != '\n') {
                throw new IOException("invalid Dawn PAX record boundaries");
            }
            String entry = new String(bytes, space + 1, cursor + length - space - 2, java.nio.charset.StandardCharsets.UTF_8);
            if (entry.startsWith("path=")) found = entry.substring(5);
            cursor += length;
        }
        return found;
    }
    private static String tarText(byte[] header, int start, int size) {
        int end = start;
        while (end < start + size && header[end] != 0) end++;
        return new String(header, start, end - start, java.nio.charset.StandardCharsets.US_ASCII);
    }
    private static long octal(byte[] bytes, int start, int length) throws IOException {
        long result = 0;
        boolean digits = false;
        for (int i = start; i < start + length; i++) {
            int c = bytes[i] & 0xff;
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') throw new IOException("unsupported Dawn tar size encoding");
            if (result > (Long.MAX_VALUE >>> 3)) throw new IOException("Dawn tar numeric overflow");
            result = (result << 3) + (c - '0');
            digits = true;
        }
        if (!digits) throw new IOException("missing Dawn tar size");
        return result;
    }
    private static void skipPadding(InputStream stream, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        if (pad > 0) stream.skipNBytes(pad);
    }
    private static void readFully(InputStream in, byte[] bytes, int count) throws IOException {
        int offset = 0;
        while (offset < count) {
            int n = in.read(bytes, offset, count - offset);
            if (n < 0) throw new IOException("truncated Dawn tar header or metadata");
            if (n > 0) offset += n;
        }
    }
    private static boolean hasDawnConfig(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            return paths.anyMatch(p -> p.getFileName().toString().equals("DawnConfig.cmake")
                    && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS));
        }
    }
    private static void verifySameTree(Path candidate, Path existing) throws IOException {
        if (!Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Dawn installation is not a regular directory");
        try (var paths = Files.walk(candidate); var present = Files.walk(existing)) {
            var a = paths.map(candidate::relativize).sorted().toList();
            var b = present.map(existing::relativize).sorted().toList();
            if (!a.equals(b)) throw new IOException("Dawn installation differs from pinned archive");
            for (Path part : a) {
                Path left = candidate.resolve(part), right = existing.resolve(part);
                if (Files.isDirectory(left, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(right, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Dawn installation type drift");
                } else if (!Files.isRegularFile(right, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(left) != Files.size(right)
                        || !MessageDigest.isEqual(fileHash(left), fileHash(right))) {
                    throw new IOException("Dawn installation bytes differ from pinned archive");
                }
            }
        }
    }
    private static byte[] fileHash(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return digest.digest();
    }
    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 missing", impossible); }
    }
}
