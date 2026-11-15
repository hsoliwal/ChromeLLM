// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

/** Maven provisioning of the exact binary inputs used by the native fork's CI. */
public final class NativeBuildArtifacts {
    static final String MODEL_SHA = "8030f04528538d47bda434f6f0bdf3952c40a58123e4d5e755332f23731a8684";
    static final long MODEL_BYTES = 105454144L;
    static final String MODEL_URL = "https://github.com/hsoliwal/ChromeLLM/releases/download/"
            + "model-smollm2-135m-q4km-8030f045/weights.bin";
    static final String DAWN_ROOT = "Dawn-5e9a4865b1635796ccc77dd30057f2b4002a1355-ubuntu-latest-Release";
    static final String DAWN_URL = "https://github.com/reeselevine/dawn/releases/download/v2.0.0/"
            + DAWN_ROOT + ".zip";
    static final String DAWN_SHA = "a5a87d9513e1fc109c355628baebe6b85fe7aa258f8ed2d2cf71ddc902e778bb";
    static final long DAWN_BYTES = 9978094L;
    static final String DAWN_TAR_SHA = "c2ee4e05d3a7e7ebd5c7267878a62d695bf075f595abadc2335ea036b31cc0d4";
    static final long DAWN_TAR_BYTES = 10022472L;

    private NativeBuildArtifacts() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 6 || !(args[4].equals("offline=true") || args[4].equals("offline=false"))) {
            throw new IllegalArgumentException("expected model|dawn, cache, project, target, offline, cmake");
        }
        String kind = args[0];
        if (!kind.equals("model") && !kind.equals("dawn")) {
            throw new IllegalArgumentException("unknown binary input: " + kind);
        }
        Path target = buildDirectory(Path.of(args[2]), Path.of(args[3]));
        if (kind.equals("dawn")) requireDawnPlatform(System.getProperty("os.name"), System.getProperty("os.arch"));
        Path cache = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(cache);
        if (!cache.toRealPath().equals(cache)) throw new IOException("binary cache must not traverse symlinks");
        boolean offline = args[4].equals("offline=true");
        if (kind.equals("model")) {
            Path model = new ModelArtifactCache(MODEL_URL, MODEL_SHA, cache).resolveWithOfflinePolicy(offline);
            if (Files.size(model) != MODEL_BYTES) throw new IOException("model binary length mismatch");
            System.out.println("Verified model binary: " + MODEL_SHA);
        } else {
            Path archive = new ModelArtifactCache(DAWN_URL, DAWN_SHA, cache, DAWN_BYTES).resolveWithOfflinePolicy(offline);
            installDawn(archive, target, args[5]);
            System.out.println("Verified Dawn binary: " + DAWN_SHA);
        }
    }

    static Path buildDirectory(Path project, Path build) throws IOException {
        Path root = project.toRealPath();
        Path target = build.toAbsolutePath().normalize();
        if (!target.equals(root.resolve("target")) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                || !target.toRealPath().equals(target)) {
            throw new IOException("binary preparation requires the existing in-project Maven target directory");
        }
        return target;
    }

    static void requireDawnPlatform(String os, String architecture) {
        if (!os.toLowerCase(Locale.ROOT).contains("linux")
                || !(architecture.equalsIgnoreCase("amd64") || architecture.equalsIgnoreCase("x86_64"))) {
            throw new IllegalArgumentException("the pinned Dawn CI binary requires Linux x86_64; "
                    + "use the existing fork-native profile with a compatible Dawn installation on other platforms");
        }
    }

    private static void installDawn(Path archive, Path target, String cmake) throws Exception {
        Path destination = target.resolve("dawn-sdk");
        if (Files.isSymbolicLink(destination)) throw new IOException("Dawn destination must not be a symlink");
        Path stage = Files.createTempDirectory(target, ".dawn-stage-");
        try {
            Path tar = stage.resolve("dawn.tar.gz");
            unpackSingleZip(archive, tar, DAWN_ROOT + ".tar.gz", DAWN_TAR_SHA, DAWN_TAR_BYTES);
            // Only these exact, hash-verified upstream archive bytes reach the existing CMake extractor.
            Process process = new ProcessBuilder(cmake, "-E", "tar", "xfz", tar.toString())
                    .directory(stage.toFile()).inheritIO().start();
            try {
                if (!process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("Dawn binary extraction timed out");
                }
                if (process.exitValue() != 0) throw new IOException("Dawn binary extraction failed");
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
            Path installed = stage.resolve(DAWN_ROOT);
            if (!Files.isRegularFile(installed.resolve("lib64/cmake/Dawn/DawnConfig.cmake"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(installed.resolve("lib64/libwebgpu_dawn.a"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(installed.resolve("include/dawn/webgpu.h"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Dawn binary package is missing its headers, library or CMake configuration");
            }
            // Re-extract verified bytes on each invocation, so edited extracted files are never trusted.
            // The old installation is retained until the replacement is complete and validated.
            deleteTree(destination);
            Files.move(installed, destination);
        } finally {
            deleteTree(stage);
        }
    }

    static void unpackSingleZip(Path archive, Path output, String expectedName,
                                String expectedSha, long expectedBytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            var entry = input.getNextEntry();
            if (entry == null || entry.isDirectory() || !entry.getName().equals(expectedName)) {
                throw new IOException("unexpected Dawn ZIP member");
            }
            try (OutputStream destination = Files.newOutputStream(output)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = input.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Dawn extraction interrupted");
                    if (n > expectedBytes - count) throw new IOException("Dawn ZIP member exceeds pinned length");
                    digest.update(buffer, 0, n);
                    destination.write(buffer, 0, n);
                    count += n;
                }
            }
            if (count != expectedBytes || !MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(expectedSha))) {
                throw new IOException("Dawn inner archive digest or length mismatch");
            }
            if (input.getNextEntry() != null) throw new IOException("unexpected extra Dawn ZIP member");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
