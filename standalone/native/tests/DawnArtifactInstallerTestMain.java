// SPDX-License-Identifier: Apache-2.0
// Offline build-tool regression: no release download or model execution.
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DawnArtifactInstallerTestMain {
    private static int checks;
    private static void check(boolean ok) {
        checks++;
        if (!ok) throw new AssertionError("check " + checks);
    }
    private static void fails(Class<? extends Exception> type, Throwing task) throws Exception {
        try { task.run(); throw new AssertionError("expected failure: " + type.getName()); }
        catch (Exception exception) { if (!type.isInstance(exception)) throw exception; checks++; }
    }
    @FunctionalInterface interface Throwing { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected test output directory");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(root)) throw new IllegalStateException("test root already exists; clean target/ first");
        Files.createDirectories(root.resolve("package/lib64/cmake/Dawn"));
        Files.writeString(root.resolve("package/lib64/cmake/Dawn/DawnConfig.cmake"), "# fixture\n");
        var linux = DawnArtifactInstaller.artifactFor("Linux", "amd64");
        var mac = DawnArtifactInstaller.artifactFor("Mac OS X", "aarch64");
        check(linux.asset().contains("ubuntu-latest"));
        check(mac.asset().contains("macos-latest"));
        check(linux.sha256().equals("a5a87d9513e1fc109c355628baebe6b85fe7aa258f8ed2d2cf71ddc902e778bb"));
        check(mac.sha256().equals("3e8d604c4b601b07cc0895396507927f2b52db60022276e435fb21bba590cfa9"));
        fails(IllegalArgumentException.class, () -> DawnArtifactInstaller.artifactFor("Windows", "amd64"));
        fails(IllegalArgumentException.class, () -> DawnArtifactInstaller.artifactFor("Linux", "aarch64"));
        Path tar = root.resolve("fixture.tar.gz");
        Process make = new ProcessBuilder("tar", "-czf", tar.toString(), "-C", root.toString(), "package")
                .inheritIO().start();
        check(make.waitFor() == 0);
        Path zip = root.resolve("fixture.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry(linux.asset() + ".tar.gz"));
            Files.copy(tar, output);
            output.closeEntry();
        }
        var fixture = new DawnArtifactInstaller.Artifact(linux.asset(), DawnArtifactInstaller.sha256(zip));
        Path prefix = root.resolve("native-deps/dawn");
        DawnArtifactInstaller.installPinned(prefix, fixture, zip);
        check(DawnArtifactInstaller.hasDawnConfig(prefix));
        check(Files.readString(prefix.resolve(".chromellm-dawn-sha256")).trim().equals(fixture.sha256()));
        DawnArtifactInstaller.installPinned(prefix, fixture, zip);
        check(DawnArtifactInstaller.hasDawnConfig(prefix));
        var tampered = new DawnArtifactInstaller.Artifact(linux.asset(), "0".repeat(64));
        fails(java.io.IOException.class, () -> DawnArtifactInstaller.installPinned(prefix, tampered, zip));
        Files.writeString(prefix.resolve(".chromellm-dawn-sha256"), "0".repeat(64));
        fails(java.io.IOException.class, () -> DawnArtifactInstaller.installPinned(prefix, fixture, zip));
        System.out.println("Dawn installer offline checks passed: " + checks);
    }
}
