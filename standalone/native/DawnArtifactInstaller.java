// SPDX-License-Identifier: Apache-2.0
// Build-time only: Java 21 source-file launcher for pinned Dawn release archives.
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Installs one SHA-256-pinned, platform-specific Dawn binary under Maven target/. */
public final class DawnArtifactInstaller {
    private static final String PREFIX = "Dawn-5e9a4865b1635796ccc77dd30057f2b4002a1355-";
    private static final String RELEASE = "https://github.com/reeselevine/dawn/releases/download/v2.0.0/";
    private static final long MAX_ZIP_BYTES = 64L * 1024 * 1024;
    private static final long MAX_TAR_BYTES = 512L * 1024 * 1024;

    record Artifact(String asset, String sha256) {
        URI uri() { return URI.create(RELEASE + asset + ".zip"); }
    }

    private DawnArtifactInstaller() { }

    static Artifact artifactFor(String operatingSystem, String architecture) {
        String os = operatingSystem.toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        if (os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            return new Artifact(PREFIX + "ubuntu-latest-Release",
                    "a5a87d9513e1fc109c355628baebe6b85fe7aa258f8ed2d2cf71ddc902e778bb");
        }
        if (os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"))) {
            return new Artifact(PREFIX + "macos-latest-Release",
                    "3e8d604c4b601b07cc0895396507927f2b52db60022276e435fb21bba590cfa9");
        }
        throw new IllegalArgumentException("no pinned Dawn binary for " + operatingSystem + "/" + architecture);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected exactly one Maven target/ Dawn prefix");
        Path prefix = Path.of(args[0]).toAbsolutePath().normalize();
        if (!containsSegment(prefix, "target") || prefix.getFileName() == null ||
                !prefix.getFileName().toString().equals("dawn")) {
            throw new IllegalArgumentException("Dawn output must be target/.../dawn");
        }
        Artifact artifact = artifactFor(System.getProperty("os.name"), System.getProperty("os.arch"));
        Path cache = prefix.getParent().resolve(artifact.asset() + ".zip");
        if (!Files.exists(cache)) {
            Path partial = cache.resolveSibling(cache.getFileName().toString() + ".partial");
            if (Files.exists(partial)) throw new IOException("partial Dawn archive exists; inspect it before retrying");
            Files.createDirectories(cache.getParent());
            download(artifact.uri(), partial);
            requireHash(partial, artifact.sha256());
            Files.move(partial, cache);
        }
        installPinned(prefix, artifact, cache);
        System.out.println("Dawn ready: " + prefix + " (SHA-256 " + artifact.sha256() + ")");
    }

    private static boolean containsSegment(Path path, String segment) {
        for (Path part : path) if (part.toString().equals(segment)) return true;
        return false;
    }

    private static void download(URI uri, Path destination) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Dawn release returned HTTP " + response.statusCode());
        }
        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(destination)) {
            copyBounded(input, output, MAX_ZIP_BYTES);
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest hash = digest();
            byte[] bytes = new byte[65536];
            int read;
            while ((read = input.read(bytes)) != -1) hash.update(bytes, 0, read);
            return HexFormat.of().formatHex(hash.digest());
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static void requireHash(Path file, String expected) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_ZIP_BYTES ||
                !sha256(file).equalsIgnoreCase(expected)) {
            throw new IOException("Dawn release SHA-256 mismatch: " + file);
        }
    }

    private static void copyBounded(InputStream input, OutputStream output, long maximum) throws IOException {
        long count = 0;
        byte[] bytes = new byte[65536];
        int read;
        while ((read = input.read(bytes)) != -1) {
            count += read;
            if (count > maximum) throw new IOException("Dawn archive exceeds configured byte limit");
            output.write(bytes, 0, read);
        }
    }

    static void installPinned(Path prefix, Artifact artifact, Path archive) throws Exception {
        requireHash(archive, artifact.sha256());
        Path marker = prefix.resolve(".chromellm-dawn-sha256");
        if (Files.exists(prefix)) {
            if (!Files.isDirectory(prefix) || !Files.isRegularFile(marker) ||
                    !Files.readString(marker).trim().equalsIgnoreCase(artifact.sha256()) ||
                    !hasDawnConfig(prefix)) {
                throw new IOException("Dawn cache is incomplete or mismatched; inspect or clean target/");
            }
            return;
        }
        Path staging = prefix.resolveSibling("dawn.staging");
        if (Files.exists(staging)) throw new IOException("Dawn staging directory exists; inspect it before retrying");
        Files.createDirectories(staging);
        Path nested = staging.resolve(artifact.asset() + ".tar.gz");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(artifact.asset() + ".tar.gz");
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_TAR_BYTES) {
                throw new IOException("pinned Dawn ZIP has no expected tar.gz entry");
            }
            try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(nested)) {
                copyBounded(input, output, MAX_TAR_BYTES);
            }
        }
        Process process = new ProcessBuilder("tar", "-xzf", nested.toString(),
                "-C", staging.toString(), "--strip-components=1")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IOException("tar could not extract the pinned Dawn archive");
        }
        Files.delete(nested);
        if (!hasDawnConfig(staging)) throw new IOException("pinned Dawn archive lacks DawnConfig.cmake");
        Files.writeString(staging.resolve(".chromellm-dawn-sha256"), artifact.sha256() + "\n",
                StandardCharsets.US_ASCII);
        Files.move(staging, prefix);
    }

    static boolean hasDawnConfig(Path prefix) {
        return Files.isRegularFile(prefix.resolve("lib64/cmake/Dawn/DawnConfig.cmake")) ||
                Files.isRegularFile(prefix.resolve("lib/cmake/Dawn/DawnConfig.cmake"));
    }
}
