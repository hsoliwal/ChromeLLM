// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
            testLargeGitStatus(root);
            System.out.println("native binary artifact contracts passed");
        } finally {
            deleteFixtureTree(root);
        }
    }

    // Close directory enumeration handles before deleting temporary Git objects
    // (required on Windows). Retry only transient locks; never ignore a leak.
    private static void deleteFixtureTree(Path root) throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            if (!Files.exists(root)) return;
            List<Path> snapshot;
            try (var paths = Files.walk(root)) {
                snapshot = paths.sorted(Comparator.reverseOrder()).toList();
            }
            try {
                for (Path item : snapshot) Files.delete(item);
                return;
            } catch (java.nio.file.AccessDeniedException |
                     java.nio.file.DirectoryNotEmptyException locked) {
                if (attempt == 9) throw locked;
                Thread.sleep(100L * (attempt + 1));
            }
        }
    }

    private static void writeZip(Path path, String name, byte[] bytes, boolean extra) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
            if (extra) { zip.putNextEntry(new ZipEntry("extra")); zip.write(0); zip.closeEntry(); }
        }
    }

    // Refuse a dirty worktree without waiting for the OS pipe buffer to drain.
    private static void testLargeGitStatus(Path root) throws Exception {
        Path source = Files.createDirectory(root.resolve("source-pin"));
        Files.createDirectories(source.resolve("include"));
        Files.createDirectories(source.resolve("ggml/include"));
        for (String relative : List.of("CMakeLists.txt", "include/llama.h",
                "ggml/include/ggml-backend.h")) {
            Files.writeString(source.resolve(relative), "# fixture\n");
        }
        git(source, "init", "-q");
        git(source, "config", "user.email", "fixture@invalid.test");
        git(source, "config", "user.name", "Fixture");
        git(source, "add", ".");
        git(source, "commit", "-qm", "source-pin fixture");
        String commit = git(source, "rev-parse", "HEAD");
        ForkSourcePin.verify(source, commit);
        reject(() -> ForkSourcePin.verify(source, "0".repeat(40)));
        for (int i = 0; i < 1400; i++) {
            Files.writeString(source.resolve("untracked-" + i + "-"
                    + "x".repeat(60) + ".txt"), "");
        }
        long start = System.nanoTime();
        reject(() -> ForkSourcePin.verify(source, commit));
        if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) >= 10) {
            throw new AssertionError("Git status output blocked process verification");
        }
    }

    private static String git(Path root, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0) throw new IOException("Git fixture failed: " + output);
        return output;
    }

    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void reject(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("expected binary admission failure"); }
        catch (IOException | IllegalArgumentException expected) { }
    }
}
