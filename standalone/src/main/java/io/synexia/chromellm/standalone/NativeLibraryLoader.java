// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Loads only the explicitly installed, matching platform artifact; never downloads native code. */
final class NativeLibraryLoader {
    private static final String MODULE_NAME = "io.synexia.chromellm.natives";
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_FILES = 128;
    private static final long MAX_FILE_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_BUNDLE_BYTES = 4 * MAX_FILE_BYTES;
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,254}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE =
            PosixFilePermissions.fromString("rw-------");

    private NativeLibraryLoader() { }

    static synchronized Path libraryPath() throws IOException {
        String classifier = classifier(System.getProperty("os.name"), System.getProperty("os.arch"));
        String configured = System.getProperty("chromellm.native.cache");
        Path root = configured == null
                ? Path.of(System.getProperty("user.home"), ".cache", "chromellm", "native")
                : Path.of(configured);
        Bundle bundle = resolve(NativeLibraryLoader.class.getClassLoader(),
                System.getProperty("jdk.module.path", ""), root, classifier,
                Boolean.getBoolean("chromellm.native.allowSynthetic"));
        // Preload verified sibling dependencies in the package's topological order. In particular,
        // Windows must not resolve a DLL with the same name from the working directory or PATH.
        // The existing JNI owner loads the bridge last and keeps its public constructors unchanged.
        for (String name : bundle.manifest().loadOrder()) {
            if (name.equals(bundle.manifest().library())) break;
            try {
                System.load(bundle.directory().resolve(name).toString());
            } catch (UnsatisfiedLinkError failure) {
                throw new IOException("cannot load packaged native dependency: " + name, failure);
            }
        }
        return bundle.directory().resolve(bundle.manifest().library());
    }

    static String classifier(String os, String arch) throws IOException {
        String system = os == null ? "" : os.toLowerCase(Locale.ROOT);
        String machine = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
        String platform;
        if (system.equals("linux")) platform = "linux";
        else if (system.startsWith("windows")) platform = "windows";
        else if (system.equals("mac os x") || system.equals("macos") || system.equals("darwin")) platform = "macos";
        else throw new IOException("no packaged JNI platform for operating system: " + os);
        String cpu = switch (machine) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new IOException("no packaged JNI platform for architecture: " + arch);
        };
        if (platform.equals("windows") && !cpu.equals("x86_64")) {
            throw new IOException("no packaged JNI platform for Windows architecture: " + arch);
        }
        return platform + "-" + cpu;
    }

    /** Package-private entry point used by the artifact extraction contract tests. */
    static synchronized Path extract(ClassLoader loader, String modulePath, Path root,
                                     String classifier, boolean allowSynthetic) throws IOException {
        Bundle bundle = resolve(loader, modulePath, root, classifier, allowSynthetic);
        return bundle.directory().resolve(bundle.manifest().library());
    }

    private static Bundle resolve(ClassLoader loader, String modulePath, Path root,
                                  String classifier, boolean allowSynthetic) throws IOException {
        String resource = "META-INF/native/" + classifier + "/manifest.properties";
        Map<String, Source> sources = new LinkedHashMap<>();
        Enumeration<URL> candidates = loader.getResources(resource);
        while (candidates.hasMoreElements()) {
            UrlSource source = new UrlSource(candidates.nextElement());
            sources.putIfAbsent(source.identity(), source);
        }
        // A resource-only automatic module is not necessarily resolved by a named application's
        // requires graph. Read precisely the native module from the declared module path instead
        // of requiring --add-modules or changing the runtime's module descriptor.
        if (!modulePath.isBlank()) {
            Path[] entries = Arrays.stream(modulePath.split(Pattern.quote(java.io.File.pathSeparator), -1))
                    .filter(value -> !value.isEmpty()).map(Path::of).toArray(Path[]::new);
            try {
                for (Path entry : entries) {
                    ModuleFinder.of(entry).find(MODULE_NAME).ifPresent(reference -> {
                        URI location = reference.location().orElse(null);
                        if (location != null && location.getScheme().equals("file")) {
                            ModuleSource source = new ModuleSource(reference, resource);
                            sources.putIfAbsent(source.identity(), source);
                        }
                    });
                }
            } catch (FindException failure) {
                throw new IOException("cannot inspect the packaged JNI module path", failure);
            }
        }
        // Resolved custom layers may have no jdk.module.path entry. Their exact native module
        // references remain eligible; package resources in arbitrary modules do not.
        ModuleLayer layer = NativeLibraryLoader.class.getModule().getLayer();
        addLayerSources(layer, resource, sources, new HashSet<>());
        if (sources.isEmpty()) {
            throw new IOException("packaged JNI artifact is missing for " + classifier
                    + "; add the matching native classifier JAR or supply --jni-lib");
        }
        if (sources.size() != 1) {
            throw new IOException("multiple packaged JNI artifacts for " + classifier);
        }
        Source source = sources.values().iterator().next();
        byte[] manifestBytes;
        try (InputStream input = source.open("manifest.properties")) {
            manifestBytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
        }
        if (manifestBytes.length > MAX_MANIFEST_BYTES) throw new IOException("native manifest is too large");
        Manifest manifest = parseManifest(manifestBytes, classifier, allowSynthetic);
        String digest = HexFormat.of().formatHex(sha256().digest(manifestBytes));
        Path cache = root.toAbsolutePath().normalize();
        createPrivateDirectory(cache);
        Path directory = cache.resolve(classifier + "-" + digest);
        Path lock = cache.resolve(classifier + "-" + digest + ".lock");
        createPrivateFile(lock);
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock held = channel.lock()) {
            if (!held.isValid()) throw new IOException("native cache lock was lost");
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                verifyBundle(directory, manifest);
            } else {
                Path staging = Files.createTempDirectory(cache, ".extract-");
                Throwable primary = null;
                try {
                    long total = 0;
                    for (String name : manifest.files()) {
                        Path destination = staging.resolve(name);
                        createPrivateFile(destination);
                        try (InputStream input = source.open(name);
                             OutputStream output = Files.newOutputStream(destination,
                                     StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                            long bytes = copyVerified(input, output, manifest.hashes().get(name), MAX_FILE_BYTES);
                            total = Math.addExact(total, bytes);
                            if (total > MAX_BUNDLE_BYTES) throw new IOException("native package exceeds 4 GiB");
                        }
                    }
                    verifyBundle(staging, manifest);
                    try {
                        Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        // A same-filesystem move under the process lock cannot expose a partially
                        // copied directory. The cache is reused only after independent verification.
                        Files.move(staging, directory);
                    }
                } catch (IOException | RuntimeException | Error failure) {
                    primary = failure;
                    throw failure;
                } finally {
                    try { deleteStaging(staging); }
                    catch (IOException cleanup) {
                        if (primary == null) throw cleanup;
                        primary.addSuppressed(cleanup);
                    }
                }
            }
        }
        return new Bundle(directory, manifest);
    }

    private static void addLayerSources(ModuleLayer layer, String resource, Map<String, Source> sources,
                                        Set<ModuleLayer> visited) throws IOException {
        if (layer == null || !visited.add(layer)) return;
        var resolved = layer.configuration().findModule(MODULE_NAME);
        if (resolved.isPresent()) {
            ModuleReference reference = resolved.get().reference();
            URI location = reference.location().orElse(null);
            if (location == null || !location.getScheme().equals("file")) {
                throw new IOException("native module must be a local artifact");
            }
            ModuleSource source = new ModuleSource(reference, resource);
            sources.putIfAbsent(source.identity(), source);
        }
        for (ModuleLayer parent : layer.parents()) addLayerSources(parent, resource, sources, visited);
    }

    private static Manifest parseManifest(byte[] bytes, String expected, boolean allowSynthetic) throws IOException {
        // The CMake producer emits this deliberately small, ASCII properties subset. Reject
        // duplicate keys and escaped names rather than silently applying Properties last-wins.
        for (byte value : bytes) {
            int c = Byte.toUnsignedInt(value);
            if (c != '\n' && c != '\r' && (c < 32 || c > 126)) throw new IOException("native manifest is not ASCII");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\\r?\\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            int split = line.indexOf('=');
            if (split <= 0 || values.putIfAbsent(line.substring(0, split), line.substring(split + 1)) != null) {
                throw new IOException("invalid or duplicate native manifest key");
            }
        }
        if (!"1".equals(values.remove("format")) || !expected.equals(values.remove("classifier"))) {
            throw new IOException("native manifest format or platform does not match");
        }
        String synthetic = values.remove("synthetic");
        if (!"false".equals(synthetic) && !("true".equals(synthetic) && allowSynthetic)) {
            throw new IOException("synthetic native artifact requires chromellm.native.allowSynthetic=true");
        }
        String library = values.remove("library");
        String expectedLibrary = expected.startsWith("windows-") ? "chromellm_llama_jni.dll"
                : expected.startsWith("macos-") ? "libchromellm_llama_jni.dylib" : "libchromellm_llama_jni.so";
        if (!expectedLibrary.equals(library)) throw new IOException("native bridge filename does not match the platform");
        List<String> files = names(values.remove("files"));
        List<String> order = names(values.remove("loadOrder"));
        if (!new HashSet<>(files).equals(new HashSet<>(order))
                || !order.getLast().equals(library)) throw new IOException("invalid native dependency load order");
        Map<String, String> hashes = new HashMap<>();
        for (String file : files) {
            String hash = values.remove("sha256." + file);
            if (hash == null || !SHA256.matcher(hash).matches()) throw new IOException("missing native SHA-256: " + file);
            hashes.put(file, hash);
        }
        if (!values.isEmpty()) throw new IOException("unknown native manifest keys");
        return new Manifest(library, files, order, Map.copyOf(hashes));
    }

    private static List<String> names(String field) throws IOException {
        if (field == null || field.isEmpty()) throw new IOException("missing native file list");
        List<String> names = List.of(field.split(",", -1));
        if (names.size() > MAX_FILES) throw new IOException("too many native files");
        Set<String> unique = new HashSet<>();
        for (String name : names) {
            if (!FILE_NAME.matcher(name).matches() || !unique.add(name.toLowerCase(Locale.ROOT))) {
                throw new IOException("unsafe or duplicate native filename");
            }
        }
        return names;
    }

    private static void verifyBundle(Path directory, Manifest manifest) throws IOException {
        verifyPrivateDirectory(directory);
        Set<String> actual = new HashSet<>();
        try (var entries = Files.list(directory)) {
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                actual.add(iterator.next().getFileName().toString());
                if (actual.size() > MAX_FILES) throw new IOException("native cache contains unexpected files");
            }
        }
        if (!actual.equals(new HashSet<>(manifest.files()))) throw new IOException("native cache contains unexpected files");
        long total = 0;
        for (String name : manifest.files()) {
            Path file = directory.resolve(name);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("native cache entry is not a regular file");
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                total = Math.addExact(total, copyVerified(input, OutputStream.nullOutputStream(),
                        manifest.hashes().get(name), MAX_FILE_BYTES));
            }
            if (total > MAX_BUNDLE_BYTES) throw new IOException("native cache exceeds 4 GiB");
        }
    }

    private static long copyVerified(InputStream input, OutputStream output, String expected,
                                     long limit) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        for (int n; (n = input.read(buffer)) != -1;) {
            total += n;
            if (total > limit) throw new IOException("native file exceeds 1 GiB");
            digest.update(buffer, 0, n);
            output.write(buffer, 0, n);
        }
        if (total == 0 || !HexFormat.of().formatHex(digest.digest()).equals(expected)) {
            throw new IOException("native file SHA-256 mismatch");
        }
        return total;
    }

    private static void createPrivateDirectory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.getFileStore(path.getRoot()).supportsFileAttributeView("posix")) {
                Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
            } else Files.createDirectories(path);
        }
        verifyPrivateDirectory(path);
    }

    private static void verifyPrivateDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("native cache directory is not a real directory");
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE) || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException("native cache directory is writable by other users");
            }
        }
    }

    private static void createPrivateFile(Path path) throws IOException {
        try {
            if (Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")) {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_FILE));
            } else Files.createFile(path);
        } catch (FileAlreadyExistsException existing) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("native cache file is not a regular file");
        }
    }

    private static void deleteStaging(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private interface Source {
        String identity();
        InputStream open(String name) throws IOException;
    }

    private static final class UrlSource implements Source {
        private final URL manifest;
        private final String identity;

        UrlSource(URL manifest) throws IOException {
            this.manifest = manifest;
            try {
                if (manifest.getProtocol().equals("jar")) {
                    JarURLConnection jar = (JarURLConnection) manifest.openConnection();
                    if (!jar.getJarFileURL().getProtocol().equals("file")) throw new IOException("native artifact must be local");
                    identity = Path.of(jar.getJarFileURL().toURI()).toAbsolutePath().normalize().toUri().toString();
                } else if (manifest.getProtocol().equals("file")) {
                    identity = manifest.toURI().normalize().toString();
                } else throw new IOException("native artifact must be local");
            } catch (URISyntaxException malformed) {
                throw new IOException("invalid native artifact location", malformed);
            }
        }

        @Override public String identity() { return identity; }

        @Override public InputStream open(String name) throws IOException {
            String base = manifest.toExternalForm();
            URLConnection connection = URI.create(base.substring(0, base.lastIndexOf('/') + 1) + name)
                    .toURL().openConnection();
            connection.setUseCaches(false);
            return connection.getInputStream();
        }
    }

    private static final class ModuleSource implements Source {
        private final ModuleReference reference;
        private final String directory;

        ModuleSource(ModuleReference reference, String resource) {
            this.reference = reference;
            this.directory = resource.substring(0, resource.lastIndexOf('/') + 1);
        }

        @Override public String identity() {
            return Path.of(reference.location().orElseThrow()).toAbsolutePath().normalize().toUri().toString();
        }

        @Override public InputStream open(String name) throws IOException {
            var reader = reference.open();
            try {
                InputStream stream = reader.open(directory + name)
                        .orElseThrow(() -> new IOException("missing native artifact resource: " + name));
                return new java.io.FilterInputStream(stream) {
                    @Override public void close() throws IOException {
                        IOException primary = null;
                        try { super.close(); } catch (IOException failure) { primary = failure; }
                        try { reader.close(); } catch (IOException cleanup) {
                            if (primary == null) primary = cleanup;
                            else primary.addSuppressed(cleanup);
                        }
                        if (primary != null) throw primary;
                    }
                };
            } catch (IOException | RuntimeException failure) {
                try { reader.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    private record Manifest(String library, List<String> files, List<String> loadOrder, Map<String, String> hashes) { }
    private record Bundle(Path directory, Manifest manifest) { }
}
