// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline extraction, platform and output-boundary contracts; no network or native tools. */
public final class NativeBuildArtifactsContractTestMain {
    private NativeBuildArtifactsContractTestMain() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("native-binary-contract-").toRealPath();
        try {
            Path target = Files.createDirectory(root.resolve("target"));
            if (!NativeBuildArtifacts.buildDirectory(root, target).equals(target)) throw new AssertionError("target");
            reject(() -> NativeBuildArtifacts.buildDirectory(root, root));
            NativeBuildArtifacts.requireDawnPlatform("Linux", "amd64");
            NativeBuildArtifacts.requireDawnPlatform("Linux", "x86_64");
            reject(() -> NativeBuildArtifacts.requireDawnPlatform("Linux", "aarch64"));
            reject(() -> NativeBuildArtifacts.requireDawnPlatform("Windows 11", "amd64"));
            byte[] bytes = {1, 2, 3, 4};
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            Path zip = root.resolve("archive.zip");
            Path output = root.resolve("output.tar.gz");
            writeZip(zip, "pinned.tar.gz", bytes, false);
            NativeBuildArtifacts.unpackSingleZip(zip, output, "pinned.tar.gz", hash, 4);
            if (!java.util.Arrays.equals(Files.readAllBytes(output), bytes)) throw new AssertionError("bytes changed");
            reject(() -> NativeBuildArtifacts.unpackSingleZip(zip, output, "pinned.tar.gz", hash, 3));
            reject(() -> NativeBuildArtifacts.unpackSingleZip(zip, output, "pinned.tar.gz", hash, 5));
            reject(() -> NativeBuildArtifacts.unpackSingleZip(zip, output, "pinned.tar.gz", "0".repeat(64), 4));
            writeZip(zip, "../escape", bytes, false);
            reject(() -> NativeBuildArtifacts.unpackSingleZip(zip, output, "pinned.tar.gz", hash, 4));
            if (Files.exists(root.getParent().resolve("escape"))) throw new AssertionError("ZIP escaped");
            writeZip(zip, "pinned.tar.gz", bytes, true);
            reject(() -> NativeBuildArtifacts.unpackSingleZip(zip, output, "pinned.tar.gz", hash, 4));
            System.out.println("native binary artifact contracts passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void writeZip(Path path, String name, byte[] bytes, boolean extra) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
            if (extra) { zip.putNextEntry(new ZipEntry("extra")); zip.write(0); zip.closeEntry(); }
        }
    }

    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void reject(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("expected binary admission failure"); }
        catch (IOException | IllegalArgumentException expected) { }
    }
}
