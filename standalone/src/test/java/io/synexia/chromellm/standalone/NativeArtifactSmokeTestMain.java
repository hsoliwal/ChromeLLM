// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        if (args.length != 3 || !(args[2].equals("true") || args[2].equals("false"))) {
            throw new IllegalArgumentException("pass main JAR, native JAR (or synthetic staging directory), and synthetic=true|false");
        }
        Path root = Files.createTempDirectory("chromellm native smoke ");
        Throwable primary = null;
        try {
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
                Result cold = launch(root, main, nativeJar, modules, synthetic, "cold-" + mode);
                requireInventory(cold);
                checks++;
                Result warm = launch(root, main, nativeJar, modules, synthetic, "warm-" + mode);
                requireInventory(warm);
                if (!cold.output().equals(warm.output())) throw new AssertionError("native device inventory changed on cached reload");
                checks += 2;
                Result api = launch(root, main, nativeJar, modules, synthetic, "api-" + mode);
                if (api.exit() != 0 || !api.output().contains("PACKAGED_API_READY")) {
                    throw new AssertionError("independent packaged Java API consumer failed: " + api);
                }
                checks++;
                if (synthetic) {
                    Result refused = launch(root, main, nativeJar, modules, false, "refused-" + mode);
                    if (refused.exit() == 0 || !refused.output().contains("allowSynthetic")) {
                        throw new AssertionError("packaged synthetic code was accepted without explicit test opt-in: " + refused);
                    }
                    checks++;
                }
            }
            // Separate JVMs share the same verified cache under the operating-system file lock.
            Process one = start(root, main, nativeJar, false, synthetic, "parallel-one");
            Process two = start(root, main, nativeJar, true, synthetic, "parallel-two");
            try {
                requireInventory(finish(one, root.resolve("parallel-one.log")));
                requireInventory(finish(two, root.resolve("parallel-two.log")));
            } finally {
                if (one.isAlive()) one.destroyForcibly();
                if (two.isAlive()) two.destroyForcibly();
                one.waitFor(10, TimeUnit.SECONDS);
                two.waitFor(10, TimeUnit.SECONDS);
            }
            checks += 2;
            System.out.println("packaged JNI classpath/modulepath smoke checks passed: " + checks
                    + (synthetic ? "; SYNTHETIC JNI binding only; no model/GPU inference"
                                 : "; real packaged library device enumeration; no model/GPU inference"));
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            IOException failure = null;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    try { Files.deleteIfExists(path); }
                    catch (IOException cleanup) {
                        if (failure == null) failure = cleanup;
                        else failure.addSuppressed(cleanup);
                    }
                }
            }
            if (failure != null) {
                if (primary == null) throw failure;
                primary.addSuppressed(failure);
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

    private static Result launch(Path root, Path main, Path nativeJar, boolean modules,
                                  boolean synthetic, String name) throws Exception {
        return finish(start(root, main, nativeJar, modules, synthetic, name), root.resolve(name + ".log"));
    }

    private static Process start(Path root, Path main, Path nativeJar, boolean modules,
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
        return builder.start();
    }

    private static Result finish(Process child, Path log) throws Exception {
        if (!child.waitFor(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS)) {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
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
}
