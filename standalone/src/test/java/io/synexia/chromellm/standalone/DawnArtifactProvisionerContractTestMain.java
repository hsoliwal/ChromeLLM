// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline native binary acquisition and safe Dawn extraction regression checks. */
public final class DawnArtifactProvisionerContractTestMain {
    private static int checks;
    private static final String RELEASE = "https://github.com/hsoliwal/dawn/releases/download/verified/Dawn-test.tar.gz";

    private DawnArtifactProvisionerContractTestMain() { }

    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("chromellm-dawn-artifact-");
        try {
            Path target = Files.createDirectory(project.resolve("target"));
            byte[] good = archive(Map.of(
                    "Dawn-verified/lib/cmake/Dawn/DawnConfig.cmake", "# synthetic Dawn config",
                    "Dawn-verified/include/webgpu/webgpu.h", "// synthetic header"));
            String digest = sha(good);
            AtomicInteger calls = new AtomicInteger();
            DawnArtifactProvisioner.Fetcher fetch = uri -> {
                calls.incrementAndGet();
                if (uri.getHost().equals("github.com")) {
                    return new DawnArtifactProvisioner.FetchResponse(302,
                            "https://release-assets.githubusercontent.com/fake-assets", "identity", 0,
                            new ByteArrayInputStream(new byte[0]));
                }
                return new DawnArtifactProvisioner.FetchResponse(200, null, "identity", good.length,
                        new ByteArrayInputStream(good));
            };
            Path output = DawnArtifactProvisioner.provision(RELEASE, digest, project, target, fetch);
            check(Files.isRegularFile(output.resolve("lib/cmake/Dawn/DawnConfig.cmake")), "missing Dawn CMake package");
            check(Files.isRegularFile(output.resolve("include/webgpu/webgpu.h")), "missing Dawn header");
            check(output.toRealPath().startsWith(target.toRealPath()), "output escaped target");
            check(calls.get() == 2, "redirect/download count mismatch");
            byte[] zip = zip(good, "Dawn-verified.tar.gz");
            Path zipWorkspace = Files.createTempDirectory(project, "zip-verified-");
            Path zipTarget = Files.createDirectory(zipWorkspace.resolve("target"));
            Path zipResult = DawnArtifactProvisioner.provision(
                    RELEASE.replace(".tar.gz", ".zip"), sha(zip), zipWorkspace, zipTarget,
                    uri -> new DawnArtifactProvisioner.FetchResponse(200, null, "identity", zip.length,
                            new ByteArrayInputStream(zip)));
            check(Files.isRegularFile(zipResult.resolve("lib/cmake/Dawn/DawnConfig.cmake")),
                    "GitHub Release ZIP wrapper failed to provision Dawn");
            byte[] wrongZip = zip(good, "../Dawn-verified.tar.gz");
            rejectIo(() -> provisionInFreshZip(project, wrongZip));
            byte[] otherZip = zip(good, "unexpected.txt");
            rejectIo(() -> provisionInFreshZip(project, otherZip));
            Path again = DawnArtifactProvisioner.provision(RELEASE, digest, project, target, uri -> {
                throw new AssertionError("cached archive unexpectedly downloaded");
            });
            check(again.equals(output), "cache replay changed target");
            Files.writeString(output.resolve("lib/cmake/Dawn/DawnConfig.cmake"), "tampered");
            rejectIo(() -> DawnArtifactProvisioner.provision(RELEASE, digest, project, target, fetch));
            check(Files.readString(output.resolve("lib/cmake/Dawn/DawnConfig.cmake")).equals("tampered"),
                    "replay silently repaired prior artifacts");
            Files.writeString(output.resolve("lib/cmake/Dawn/DawnConfig.cmake"), "# synthetic Dawn config");
            Path cache = target.resolve("native-webgpu-assets/dawn-" + digest + ".tar.gz");
            byte[] corrupted = Arrays.copyOf(good, good.length);
            corrupted[0] ^= 0x03;
            Files.write(cache, corrupted);
            rejectIo(() -> DawnArtifactProvisioner.provision(RELEASE, digest, project, target, fetch));
            check(Arrays.equals(Files.readAllBytes(cache), corrupted), "corrupt cache silently repaired");
            rejectIllegal(() -> DawnArtifactProvisioner.provision("http://github.com/hsoliwal/dawn/releases/download/x/a.tar.gz",
                    digest, project, target, fetch));
            rejectIllegal(() -> DawnArtifactProvisioner.provision("https://attacker.invalid/x.tar.gz",
                    digest, project, target, fetch));
            rejectIllegal(() -> DawnArtifactProvisioner.provision(RELEASE, "unverified", project, target, fetch));
            rejectIo(() -> provisionInFresh(project, archive(Map.of(
                    "Dawn-verified/../escape", "no", "Dawn-verified/lib/cmake/Dawn/DawnConfig.cmake", "x"))));
            rejectIo(() -> provisionInFresh(project, archive(Map.of("Other-root/lib/cmake/Dawn/DawnConfig.cmake", "no"))));
            rejectIo(() -> provisionInFresh(project, archive(Map.of("Dawn-verified/README.txt", "no"))));
            byte[] invalidSymlink = tarWithSymlink();
            rejectIo(() -> provisionInFresh(project, invalidSymlink));
            byte[] unexpected = archive(Map.of("Dawn-verified/lib/cmake/Dawn/DawnConfig.cmake", "x"));
            rejectIo(() -> provisionInFresh(project, unexpected,
                    uri -> new DawnArtifactProvisioner.FetchResponse(302,
                            "https://internal.invalid/payload", "identity", 0,
                            new ByteArrayInputStream(new byte[0]))));
            rejectIo(() -> provisionInFresh(project, unexpected,
                    uri -> new DawnArtifactProvisioner.FetchResponse(200, null, "gzip", unexpected.length,
                            new ByteArrayInputStream(unexpected))));
            System.out.println("Dawn binary artifact contract checks passed: " + checks);
        } finally { delete(project); }
    }

    private static void provisionInFreshZip(Path root, byte[] bytes) throws Exception {
        Path workspace = Files.createTempDirectory(root, "zip-case-");
        try {
            Path target = Files.createDirectory(workspace.resolve("target"));
            DawnArtifactProvisioner.provision(RELEASE.replace(".tar.gz", ".zip"), sha(bytes), workspace,
                    target, uri -> new DawnArtifactProvisioner.FetchResponse(200, null, "identity",
                            bytes.length, new ByteArrayInputStream(bytes)));
        } finally { delete(workspace); }
    }
    private static byte[] zip(byte[] tar, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream stream = new ZipOutputStream(output)) {
            stream.putNextEntry(new ZipEntry(name));
            stream.write(tar);
            stream.closeEntry();
        }
        return output.toByteArray();
    }
    private static void provisionInFresh(Path root, byte[] bytes) throws Exception {
        provisionInFresh(root, bytes, uri -> new DawnArtifactProvisioner.FetchResponse(200, null,
                "identity", bytes.length, new ByteArrayInputStream(bytes)));
    }
    private static void provisionInFresh(Path root, byte[] bytes, DawnArtifactProvisioner.Fetcher fetcher)
            throws Exception {
        Path workspace = Files.createTempDirectory(root, "case-");
        try {
            Path target = Files.createDirectory(workspace.resolve("target"));
            DawnArtifactProvisioner.provision(RELEASE, sha(bytes), workspace, target, fetcher);
        } finally { delete(workspace); }
    }
    private static byte[] archive(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            byte[] body = entry.getValue().getBytes(StandardCharsets.UTF_8);
            byte[] header = header(entry.getKey(), body.length, '0');
            raw.write(header);
            raw.write(body);
            int remainder = body.length % 512;
            if (remainder != 0) raw.write(new byte[512 - remainder]);
        }
        raw.write(new byte[1024]);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(compressed)) { gz.write(raw.toByteArray()); }
        return compressed.toByteArray();
    }
    private static byte[] tarWithSymlink() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(header("Dawn-verified/badlink", 0, '2'));
        raw.write(new byte[1024]);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(compressed)) { gz.write(raw.toByteArray()); }
        return compressed.toByteArray();
    }
    private static byte[] header(String name, int length, char type) {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        if (n.length > 100) throw new IllegalArgumentException("fixture path exceeds tar name size");
        System.arraycopy(n, 0, h, 0, n.length);
        byte[] size = String.format("%011o", length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, h, 124, size.length);
        h[156] = (byte) type;
        return h;
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void delete(Path folder) throws Exception {
        if (!Files.exists(folder)) return;
        try (var files = Files.walk(folder)) {
            for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    @FunctionalInterface interface Operation { void run() throws Exception; }
    private static void rejectIo(Operation op) throws Exception {
        try { op.run(); throw new AssertionError("invalid archive was accepted"); }
        catch (IOException expected) { checks++; }
    }
    private static void rejectIllegal(Operation op) throws Exception {
        try { op.run(); throw new AssertionError("invalid URL/pin was accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean result, String detail) {
        checks++;
        if (!result) throw new AssertionError(detail);
    }
}
