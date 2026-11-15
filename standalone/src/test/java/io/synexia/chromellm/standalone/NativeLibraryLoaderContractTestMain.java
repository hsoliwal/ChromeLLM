// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Offline platform-artifact validation, corruption and concurrent extraction checks; no model inference. */
public final class NativeLibraryLoaderContractTestMain {
    private static final String CLASSIFIER = "linux-x86_64";
    private static final String JNI = "libchromellm_llama_jni.so";
    private static final String DEPENDENCY = "libfixture.so";
    private static int checks;
    private NativeLibraryLoaderContractTestMain() { }

    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("chromellm-native-contract-");
        try {
            platforms();
            boolean symbolicLinks = supportsSymbolicLinks(base);
            Path source = base.resolve("source");
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put(DEPENDENCY, "dependency fixture; not a native library".getBytes(StandardCharsets.UTF_8));
            files.put(JNI, "JNI fixture; not a native library".getBytes(StandardCharsets.UTF_8));
            String manifest = manifest(files, false);
            writeDirectory(source, manifest, files);
            try (URLClassLoader loader = loader(source)) {
                Path cache = base.resolve("cache");
                Path first = NativeLibraryLoader.extract(loader, "", cache, CLASSIFIER, false);
                check(Files.readString(first).startsWith("JNI fixture"), "manifest resources materialize exactly");
                check(NativeLibraryLoader.extract(loader, "", cache, CLASSIFIER, false).equals(first), "cache identity is stable");
                Files.writeString(first, "corrupt", StandardOpenOption.TRUNCATE_EXISTING);
                reject("SHA-256", () -> NativeLibraryLoader.extract(loader, "", cache, CLASSIFIER, false));
                Files.write(first, files.get(JNI));
                Path unexpected = first.getParent().resolve("unlisted.so");
                Files.writeString(unexpected, "must not become an implicit dependency");
                reject("unexpected", () -> NativeLibraryLoader.extract(loader, "", cache, CLASSIFIER, false));
                Files.delete(unexpected);
                if (symbolicLinks) {
                    Files.delete(first);
                    Files.createSymbolicLink(first, source.resolve("META-INF/native/" + CLASSIFIER + "/" + JNI));
                    reject("regular file", () -> NativeLibraryLoader.extract(loader, "", cache, CLASSIFIER, false));
                    Files.delete(first);
                }
            }
            Path jar = base.resolve("native.jar");
            writeJar(jar, manifest, files);
            try (URLClassLoader loader = loader(jar)) {
                Path extracted = NativeLibraryLoader.extract(loader, "", base.resolve("jar-cache"), CLASSIFIER, false);
                check(Files.readAllBytes(extracted).length == files.get(JNI).length, "JAR extraction streams the native entry");
                if (symbolicLinks) {
                    Path linkedCache = base.resolve("linked-cache");
                    Files.createSymbolicLink(linkedCache, base.resolve("jar-cache"));
                    reject("real directory", () -> NativeLibraryLoader.extract(loader, "", linkedCache, CLASSIFIER, false));
                }
            }
            try (URLClassLoader empty = loader()) {
                Path before = base.resolve("missing-cache");
                reject("missing", () -> NativeLibraryLoader.extract(empty, "", before, CLASSIFIER, false));
                check(!Files.exists(before), "missing artifact does not create a cache");
                Path fromModule = NativeLibraryLoader.extract(empty, jar.toString(), base.resolve("module-cache"), CLASSIFIER, false);
                check(Files.readString(fromModule).startsWith("JNI fixture"), "unresolved automatic module resource is found");
            }
            try (URLClassLoader loader = loader(jar)) {
                check(Files.exists(NativeLibraryLoader.extract(loader, jar.toString(),
                        base.resolve("deduplicated-cache"), CLASSIFIER, false)), "same jar observed through classpath and modulepath is not ambiguous");
            }
            Path duplicateJar = base.resolve("duplicate.jar");
            Files.copy(jar, duplicateJar);
            try (URLClassLoader loader = loader(jar, duplicateJar)) {
                reject("multiple", () -> NativeLibraryLoader.extract(loader, "", base.resolve("duplicate-cache"), CLASSIFIER, false));
            }
            try (URLClassLoader empty = loader()) {
                reject("multiple", () -> NativeLibraryLoader.extract(empty,
                        jar + java.io.File.pathSeparator + duplicateJar,
                        base.resolve("duplicate-module-cache"), CLASSIFIER, false));
            }
            Map<String, String> bad = new LinkedHashMap<>();
            bad.put("duplicate key", manifest + "format=1\n");
            bad.put("platform", manifest.replace("classifier=" + CLASSIFIER, "classifier=windows-x86_64"));
            bad.put("bridge", manifest.replace("library=" + JNI, "library=unexpected.so"));
            bad.put("hash", manifest.replace("sha256." + JNI + "=", "sha256." + JNI + "=x"));
            bad.put("unknown", manifest + "unreviewed=value\n");
            bad.put("order", manifest.replace("loadOrder=" + DEPENDENCY + "," + JNI, "loadOrder=" + JNI + "," + DEPENDENCY));
            bad.put("missing dependency", manifest.replace("loadOrder=" + DEPENDENCY + "," + JNI, "loadOrder=" + JNI));
            bad.put("duplicate filename", manifest.replace("files=" + DEPENDENCY + "," + JNI, "files=" + DEPENDENCY + "," + DEPENDENCY + "," + JNI));
            bad.put("traversal", manifest.replace("files=" + DEPENDENCY, "files=../" + DEPENDENCY));
            bad.put("unicode", manifest + "é=value\n");
            bad.put("manifest bound", manifest + "#".repeat(65536));
            int n = 0;
            for (Map.Entry<String, String> invalid : bad.entrySet()) {
                Path candidate = base.resolve("invalid-" + n++ + ".jar");
                writeJar(candidate, invalid.getValue(), files);
                try (URLClassLoader loader = loader(candidate)) {
                    reject("", () -> NativeLibraryLoader.extract(loader, "", base.resolve("invalid-cache"), CLASSIFIER, false));
                }
            }
            Map<String, byte[]> corrupted = new LinkedHashMap<>(files);
            corrupted.put(JNI, "different bytes".getBytes(StandardCharsets.UTF_8));
            Path corruptedJar = base.resolve("corrupted.jar");
            writeJar(corruptedJar, manifest, corrupted);
            Path failedCache = base.resolve("corrupted-cache");
            try (URLClassLoader loader = loader(corruptedJar)) {
                reject("SHA-256", () -> NativeLibraryLoader.extract(loader, "", failedCache, CLASSIFIER, false));
                try (var paths = Files.list(failedCache)) {
                    check(paths.noneMatch(path -> path.getFileName().toString().startsWith(".extract-")), "failed extraction removes staging files");
                }
            }
            Path syntheticJar = base.resolve("synthetic.jar");
            writeJar(syntheticJar, manifest(files, true), files);
            try (URLClassLoader loader = loader(syntheticJar)) {
                reject("allowSynthetic", () -> NativeLibraryLoader.extract(loader, "", base.resolve("synthetic-cache"), CLASSIFIER, false));
                check(Files.exists(NativeLibraryLoader.extract(loader, "", base.resolve("synthetic-cache"), CLASSIFIER, true)), "explicit synthetic testing is isolated");
            }
            try (var workers = Executors.newFixedThreadPool(4)) {
                List<Callable<Path>> requests = new ArrayList<>();
                for (int i = 0; i < 12; i++) requests.add(() -> {
                    try (URLClassLoader loader = loader(jar)) {
                        return NativeLibraryLoader.extract(loader, "", base.resolve("concurrent-cache"), CLASSIFIER, false);
                    }
                });
                Path expected = null;
                for (var result : workers.invokeAll(requests)) {
                    Path path = result.get();
                    if (expected == null) expected = path;
                    check(expected.equals(path), "concurrent callers reuse the same verified artifact");
                }
            }
            check(Files.readString(source.resolve("META-INF/native/" + CLASSIFIER + "/" + JNI)).startsWith("JNI fixture"), "source artifact is never modified");
        } finally {
            try (var paths = Files.walk(base)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("packaged JNI extraction contract checks passed: " + checks
                + "; no native model or GPU inference");
    }

    private static boolean supportsSymbolicLinks(Path root) throws IOException {
        Path link = root.resolve("link-capability");
        try {
            Files.createSymbolicLink(link, root);
            return true;
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            System.out.println("SYMLINK CONTRACTS NOT RUN: platform or account does not permit symbolic links");
            return false;
        } finally {
            Files.deleteIfExists(link);
        }
    }

    private static void platforms() throws Exception {
        check(NativeLibraryLoader.classifier("Linux", "amd64").equals("linux-x86_64"), "Linux AMD64 normalization");
        check(NativeLibraryLoader.classifier("Linux", "aarch64").equals("linux-aarch64"), "Linux ARM64 normalization");
        check(NativeLibraryLoader.classifier("Windows 11", "amd64").equals("windows-x86_64"), "Windows normalization");
        check(NativeLibraryLoader.classifier("Mac OS X", "x86_64").equals("macos-x86_64"), "macOS x64 normalization");
        check(NativeLibraryLoader.classifier("Mac OS X", "arm64").equals("macos-aarch64"), "macOS ARM64 normalization");
        reject("operating system", () -> NativeLibraryLoader.classifier("freebsd", "amd64"));
        reject("architecture", () -> NativeLibraryLoader.classifier("Linux", "x86"));
        reject("Windows", () -> NativeLibraryLoader.classifier("Windows 11", "aarch64"));
    }

    private static String manifest(Map<String, byte[]> files, boolean synthetic) throws Exception {
        String names = String.join(",", files.keySet());
        StringBuilder text = new StringBuilder("format=1\nclassifier=" + CLASSIFIER + "\nlibrary=" + JNI
                + "\nsynthetic=" + synthetic + "\nfiles=" + names + "\nloadOrder=" + names + "\n");
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            text.append("sha256.").append(file.getKey()).append('=').append(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(file.getValue()))).append('\n');
        }
        return text.toString();
    }

    private static void writeDirectory(Path root, String manifest, Map<String, byte[]> files) throws IOException {
        Path directory = root.resolve("META-INF/native/" + CLASSIFIER);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("manifest.properties"), manifest);
        for (Map.Entry<String, byte[]> file : files.entrySet()) Files.write(directory.resolve(file.getKey()), file.getValue());
    }

    private static void writeJar(Path target, String text, Map<String, byte[]> files) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "io.synexia.chromellm.natives");
        String prefix = "META-INF/native/" + CLASSIFIER + "/";
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            jar.putNextEntry(new JarEntry(prefix + "manifest.properties"));
            jar.write(text.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                jar.putNextEntry(new JarEntry(prefix + file.getKey()));
                jar.write(file.getValue());
                jar.closeEntry();
            }
        }
    }

    private static URLClassLoader loader(Path... paths) throws Exception {
        URL[] urls = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) urls[i] = paths[i].toUri().toURL();
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void reject(String expected, CheckedAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("invalid native artifact accepted: " + expected);
        } catch (IOException failure) {
            check(failure.getMessage().contains(expected), "expected " + expected + "; received " + failure);
        }
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
