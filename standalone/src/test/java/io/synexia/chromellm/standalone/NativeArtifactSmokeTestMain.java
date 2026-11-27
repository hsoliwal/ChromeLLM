// SPDX-License-Identifier: MIT
// Windows lifecycle additions: Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project; Apache-2.0.
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Executes packaged JNI outside staging; only the synthetic test mode opens a fake model fixture. */
public final class NativeArtifactSmokeTestMain {
    private NativeArtifactSmokeTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--lifecycle-self-test")) {
            lifecycleSelfTest();
            return;
        }
        if (args.length != 3 || !(args[2].equals("true") || args[2].equals("false"))) {
            throw new IllegalArgumentException("pass main JAR, native JAR (or synthetic staging directory), and synthetic=true|false");
        }
        Path root = Files.createTempDirectory("chromellm native smoke ");
        Throwable primary = null;
        try (ChildProcesses children = new ChildProcesses()) {
            lifecycleSelfTest();
            Path main = Files.copy(Path.of(args[0]), root.resolve("chromellm-standalone.jar"));
            boolean synthetic = Boolean.parseBoolean(args[2]);
            Path nativeSource = Path.of(args[1]);
            Path nativeJar = root.resolve("chromellm-natives.jar");
            if (Files.isDirectory(nativeSource, LinkOption.NOFOLLOW_LINKS)) {
                if (!synthetic) throw new IllegalArgumentException("production proof requires the actual packaged native JAR");
                createTestJar(nativeSource, nativeJar);
            } else Files.copy(nativeSource, nativeJar);
            createConsumerJar(root.resolve("consumer.jar"));
            int checks = 0;
            for (boolean modules : List.of(false, true)) {
                String mode = modules ? "modulepath" : "classpath";
                Result cold = launch(children, root, main, nativeJar, modules, synthetic, "cold-" + mode);
                requireInventory(cold);
                checks++;
                Result warm = launch(children, root, main, nativeJar, modules, synthetic, "warm-" + mode);
                requireInventory(warm);
                if (!cold.output().equals(warm.output())) throw new AssertionError("native device inventory changed on cached reload");
                checks += 2;
                Result api = launch(children, root, main, nativeJar, modules, synthetic, "api-" + mode);
                if (api.exit() != 0 || !api.output().contains("PACKAGED_API_READY")) {
                    throw new AssertionError("independent packaged Java API consumer failed: " + api);
                }
                checks++;
                if (synthetic) {
                    Result refused = launch(children, root, main, nativeJar, modules, false, "refused-" + mode);
                    if (refused.exit() == 0 || !refused.output().contains("allowSynthetic")) {
                        throw new AssertionError("packaged synthetic code was accepted without explicit test opt-in: " + refused);
                    }
                    checks++;
                }
            }
            // Separate JVMs share the same verified cache under the operating-system file lock.
            Process one = start(children, root, main, nativeJar, false, synthetic, "parallel-one");
            Process two = start(children, root, main, nativeJar, true, synthetic, "parallel-two");
            requireInventory(finish(one, root.resolve("parallel-one.log")));
            requireInventory(finish(two, root.resolve("parallel-two.log")));
            checks += 2;
            System.out.println("packaged JNI classpath/modulepath smoke checks passed: " + checks
                    + (synthetic ? "; SYNTHETIC JNI binding only; no model/GPU inference"
                                 : "; real packaged library device enumeration; no model/GPU inference"));
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try { deleteTree(root); }
            catch (IOException cleanup) {
                if (primary == null) throw cleanup;
                primary.addSuppressed(cleanup);
            }
        }
    }

    private static void createConsumerJar(Path jar) throws IOException {
        String entry = "io/synexia/chromellm/standalone/consumer/PackagedRuntimeConsumer.class";
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var source = NativeArtifactSmokeTestMain.class.getClassLoader().getResourceAsStream(entry);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            if (source == null) throw new IOException("packaged API consumer test class is missing");
            output.putNextEntry(new JarEntry(entry));
            source.transferTo(output);
            output.closeEntry();
        }
    }

    private static void createTestJar(Path stage, Path jar) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "io.synexia.chromellm.natives");
        Path resources = stage.resolve("META-INF/native");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest);
             var files = Files.walk(resources)) {
            for (Path file : files.sorted().toList()) {
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("synthetic stage contains a nonregular file");
                }
                output.putNextEntry(new JarEntry(stage.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    private static Result launch(ChildProcesses children, Path root, Path main, Path nativeJar, boolean modules,
                                  boolean synthetic, String name) throws Exception {
        return finish(start(children, root, main, nativeJar, modules, synthetic, name), root.resolve(name + ".log"));
    }

    private static Process start(ChildProcesses children, Path root, Path main, Path nativeJar, boolean modules,
                                  boolean synthetic, String name) throws IOException {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Xcheck:jni");
        command.add("-Dchromellm.native.cache=" + root.resolve(name.startsWith("parallel-") ? "parallel-cache" : "cache"));
        if (synthetic) command.add("-Dchromellm.native.allowSynthetic=true");
        boolean api = name.startsWith("api-");
        command.add(modules ? "--module-path" : "--class-path");
        command.add(main + java.io.File.pathSeparator + nativeJar
                + (!modules && api ? java.io.File.pathSeparator + root.resolve("consumer.jar") : ""));
        if (api) {
            if (modules) {
                command.add("--add-modules");
                command.add("io.synexia.chromellm.standalone");
                command.add("--class-path");
                command.add(root.resolve("consumer.jar").toString());
            }
            command.add("io.synexia.chromellm.standalone.consumer.PackagedRuntimeConsumer");
            command.add(Boolean.toString(synthetic));
        } else {
            if (modules) {
                command.add("--module");
                command.add("io.synexia.chromellm.standalone/io.synexia.chromellm.standalone.StandaloneMain");
            } else command.add("io.synexia.chromellm.standalone.StandaloneMain");
            command.add("--list-devices");
            command.add("true");
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(root.resolve(name + ".log").toFile());
        // The package must carry its dependency closure. Existing development-library search
        // variables must not accidentally make a broken relocated artifact pass.
        if (System.getProperty("os.name").startsWith("Windows")) {
            String windows = builder.environment().get("SystemRoot");
            if (windows != null) builder.environment().put("PATH", Path.of(windows, "System32").toString());
        }
        builder.environment().remove("LD_LIBRARY_PATH");
        builder.environment().remove("LD_PRELOAD");
        builder.environment().remove("DYLD_LIBRARY_PATH");
        builder.environment().remove("DYLD_FALLBACK_LIBRARY_PATH");
        builder.environment().remove("DYLD_INSERT_LIBRARIES");
        return children.own(builder.start());
    }

    private static Result finish(Process child, Path log) throws Exception {
        if (!child.waitFor(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AssertionError("packaged JNI child exceeded its deadline");
        }
        try (var input = Files.newInputStream(log)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new AssertionError("native inventory output exceeded 1 MiB");
            return new Result(child.exitValue(), new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static void requireInventory(Result result) {
        if (result.exit() != 0 || result.output().lines().noneMatch(line -> {
            String[] fields = line.split("\\t", -1);
            return fields.length == 4 && !fields[0].isBlank() && !fields[1].isBlank()
                    && List.of("CPU", "GPU", "IGPU", "ACCEL").contains(fields[2]);
        })) throw new AssertionError("packaged JNI device enumeration failed: " + result);
    }

    private record Result(int exit, String output) { }

    /** Every started child is owned before another launch or assertion can fail. */
    private static final class ChildProcesses implements AutoCloseable {
        private final List<Process> children = new ArrayList<>();
        private boolean closed;
        Process own(Process child) { children.add(child); return child; }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = null;
            boolean interrupted = Thread.interrupted();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try {
                for (Process child : children) {
                    try { if (child.isAlive()) child.destroyForcibly(); }
                    catch (RuntimeException stop) {
                        IOException failed = new IOException("cannot stop packaged JNI child " + child.pid(), stop);
                        if (failure == null) failure = failed;
                        else failure.addSuppressed(failed);
                    }
                }
                for (Process child : children) {
                    boolean terminated = !child.isAlive();
                    while (!terminated && deadline - System.nanoTime() > 0) {
                        try {
                            terminated = child.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                            break;
                        } catch (InterruptedException wait) { interrupted = true; }
                    }
                    if (!terminated || child.isAlive()) {
                        IOException failed = new IOException("packaged JNI child " + child.pid() + " did not terminate within 10 seconds");
                        if (failure == null) failure = failed;
                        else failure.addSuppressed(failed);
                    }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
            if (failure != null) throw failure;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        List<Path> entries;
        try (var paths = Files.walk(root)) { entries = paths.sorted(Comparator.reverseOrder()).toList(); }
        // Close traversal handles before deletion; all child scopes have already terminated.
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        IOException failure = null;
        for (Path entry : entries) {
            try { deleteWithRetry(entry, windows, deadline, Files::deleteIfExists); }
            catch (IOException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }

    private static void deleteWithRetry(Path path, boolean windows, long deadline, DeleteFile deletion) throws IOException {
        while (true) {
            try { deletion.delete(path); return; }
            catch (IOException failure) {
                if (!windows || !transientWindowsLock(failure) || deadline - System.nanoTime() <= 0) throw failure;
                try { TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(25), Math.max(0, deadline - System.nanoTime()))); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    IOException cleanup = new IOException("interrupted while deleting native smoke cache " + path, interrupted);
                    cleanup.addSuppressed(failure);
                    throw cleanup;
                }
            }
        }
    }

    private static boolean transientWindowsLock(IOException failure) {
        if (failure instanceof AccessDeniedException) return true;
        if (!(failure instanceof FileSystemException filesystem) || filesystem.getReason() == null) return false;
        String reason = filesystem.getReason().toLowerCase(java.util.Locale.ROOT);
        return reason.contains("used by another process") || reason.contains("sharing violation");
    }

    @FunctionalInterface private interface DeleteFile { void delete(Path path) throws IOException; }

    private static void lifecycleSelfTest() throws Exception {
        int checks = 0;
        TestProcess abandoned = new TestProcess(false);
        IOException launchFailure = new IOException("second child did not start");
        try (ChildProcesses children = new ChildProcesses()) {
            children.own(abandoned);
            throw launchFailure;
        } catch (IOException observed) {
            if (observed != launchFailure || !abandoned.destroyed || abandoned.isAlive()) {
                throw new AssertionError("failed parallel launch leaked its first child", observed);
            }
            checks++;
        }
        TestProcess refusing = new TestProcess(true);
        TestProcess later = new TestProcess(false);
        try (ChildProcesses children = new ChildProcesses()) {
            children.own(refusing);
            children.own(later);
        } catch (IOException required) {
            if (!required.getMessage().contains("did not terminate") || !refusing.destroyed || !later.destroyed
                    || later.isAlive() || refusing.waitNanos > TimeUnit.SECONDS.toNanos(10)) throw new AssertionError(required);
            checks++;
        }
        if (checks != 2) throw new AssertionError("unterminated child was accepted");
        Thread.currentThread().interrupt();
        try (ChildProcesses children = new ChildProcesses()) { children.own(new TestProcess(false)); }
        finally {
            if (!Thread.interrupted()) throw new AssertionError("child cleanup lost interruption");
            checks++;
        }
        Path owned = Path.of("owned-native-smoke-test.dll");
        AccessDeniedException locked = new AccessDeniedException(owned.toString());
        int[] calls = {0};
        deleteWithRetry(owned, true, System.nanoTime() + TimeUnit.SECONDS.toNanos(1), path -> {
            if (++calls[0] == 1) throw locked;
        });
        if (calls[0] != 2) throw new AssertionError("transient Windows lock was not retried");
        checks++;
        for (boolean windows : List.of(false, true)) {
            calls[0] = 0;
            try {
                deleteWithRetry(owned, windows, System.nanoTime() - 1, path -> { calls[0]++; throw locked; });
                throw new AssertionError("permanent cleanup failure was ignored");
            } catch (IOException observed) {
                if (observed != locked || calls[0] != 1) throw new AssertionError("cleanup failure identity or deadline changed", observed);
                checks++;
            }
        }
        IOException unrelated = new IOException("not a sharing failure");
        calls[0] = 0;
        try {
            deleteWithRetry(owned, true, System.nanoTime() + TimeUnit.SECONDS.toNanos(1), path -> { calls[0]++; throw unrelated; });
            throw new AssertionError("unrelated cleanup failure was ignored");
        } catch (IOException observed) {
            if (observed != unrelated || calls[0] != 1) throw new AssertionError("unrelated failure was retried", observed);
            checks++;
        }
        System.out.println("native smoke lifecycle checks passed: " + checks + "; no native code executed");
    }

    private static final class TestProcess extends Process {
        private final boolean refusesTermination;
        private boolean alive = true;
        private boolean destroyed;
        private long waitNanos;
        TestProcess(boolean refusesTermination) { this.refusesTermination = refusesTermination; }
        @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
        @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
        @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
        @Override public int waitFor() { if (alive) throw new IllegalStateException("test process remains alive"); return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { waitNanos = unit.toNanos(timeout); return !alive; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() { destroyed = true; if (!refusesTermination) alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
        @Override public long pid() { return 1; }
    }
}
