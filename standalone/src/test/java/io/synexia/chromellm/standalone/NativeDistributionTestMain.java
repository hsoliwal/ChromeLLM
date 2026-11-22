// SPDX-License-Identifier: MIT
// Compiler/loader fixtures only. Nothing produced here is a Maven attachment.
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class NativeDistributionTestMain {
    private static int checks;
    private static Path nativeSource;
    private record Result(int status, String output) {}
    private static Result run(Path directory, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().remove("LD_LIBRARY_PATH");
        builder.environment().remove("DYLD_LIBRARY_PATH");
        if (System.getProperty("os.name").startsWith("Windows")) {
            builder.environment().put("PATH", directory.resolve("lib") + ";" + builder.environment().get("PATH"));
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }
    private static void check(boolean value, Object message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void success(Result result) { check(result.status == 0, result.output); }
    private static void remove(Path path) throws IOException {
        try (var files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
    private static void refusedConfiguration(Path root, String classifier, boolean synthetic) throws Exception {
        Path build = Files.createTempDirectory(root, "refusal-");
        var command = new ArrayList<>(List.of("cmake", "-S", nativeSource.toString(), "-B", build.toString(),
                "-DCHROMELLM_NATIVE_DISTRIBUTION=ON", "-DCHROMELLM_NATIVE_CLASSIFIER=" + classifier));
        if (synthetic) command.add("-DCHROMELLM_SYNTHETIC_TEST=ON");
        else if (!classifier.equals("linux-x86_64")) command.add("-DCHROMELLM_LLAMA_SOURCE_DIR=" + nativeSource);
        Result result = run(root, command.toArray(String[]::new));
        check(result.status != 0, result.output);
        check(result.output.contains(synthetic || classifier.equals("linux-x86_64")
                ? "distribution requires real source" : "native classifier"), result.output);
    }
    private static void fixture(Path root, String dependency, boolean missing, boolean external) throws Exception {
        Path source = Files.createTempDirectory(root, "source-");
        Path build = Files.createTempDirectory(root, "build-");
        Files.writeString(source.resolve("dependency.cpp"), "extern \"C\" int answer() { return 42; }\n");
        Files.writeString(source.resolve("bridge.cpp"), "#include <jni.h>\nextern \"C\" int answer();\nextern \"C\" JNIEXPORT jint JNICALL Java_Consumer_answer(JNIEnv*, jclass) { return answer(); }\n");
        Files.writeString(source.resolve("CMakeLists.txt"), """
                cmake_minimum_required(VERSION 3.21)
                project(distribution_fixture LANGUAGES CXX)
                # The fixture needs JNI headers. On macOS FindJNI may not infer
                # libjvm.dylib from a selected Temurin JDK even when it exists.
                # Supply the exact local JVM library as a hint; still require JNI.
                if(APPLE AND NOT JAVA_JVM_LIBRARY AND DEFINED ENV{JAVA_HOME})
                  set(_chromellm_jvm "$ENV{JAVA_HOME}/lib/server/libjvm.dylib")
                  if(EXISTS "${_chromellm_jvm}")
                    set(JAVA_JVM_LIBRARY "${_chromellm_jvm}" CACHE FILEPATH "Selected JDK JVM library")
                  endif()
                endif()
                find_package(JNI REQUIRED)
                include("%s/NativeDistribution.cmake")
                chromellm_relocatable_libraries()
                add_library(%s SHARED dependency.cpp)
                set_target_properties(%s PROPERTIES VERSION 1.2 SOVERSION 1 WINDOWS_EXPORT_ALL_SYMBOLS ON)
                add_library(bridge SHARED bridge.cpp)
                target_include_directories(bridge PRIVATE ${JNI_INCLUDE_DIRS})
                target_link_libraries(bridge PRIVATE %s)
                chromellm_runtime_archive(bridge fixture-only "${CMAKE_BINARY_DIR}/fixture-only.zip")
                """.formatted(nativeSource.toString().replace('\\', '/'), dependency, dependency, dependency));
        if (external) {
            Files.writeString(source.resolve("CMakeLists.txt"), "\nfind_package(OpenMP REQUIRED)\ntarget_link_libraries(bridge PRIVATE OpenMP::OpenMP_CXX)\n", java.nio.file.StandardOpenOption.APPEND);
            Files.writeString(source.resolve("bridge.cpp"), "#include <omp.h>\nextern \"C\" int keep_openmp() { return omp_get_max_threads(); }\n", java.nio.file.StandardOpenOption.APPEND);
        }
        success(run(root, "cmake", "-S", source.toString(), "-B", build.toString()));
        success(run(root, "cmake", "--build", build.toString(), "--config", "Release"));
        if (missing) {
            try (var files = Files.walk(build)) {
                for (Path path : files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().contains(dependency))
                        .filter(p -> p.toString().endsWith(".dll") || p.toString().contains(".so") || p.toString().endsWith(".dylib")).toList()) {
                    Files.delete(path);
                }
            }
            Result result = run(root, "cmake", "--install", build.toString(), "--config", "Release", "--component", "chromellm-runtime");
            check(result.status != 0, result.output);
            return;
        }
        Result bundled = run(root, "cmake", "--build", build.toString(), "--target", "native-bundle", "--config", "Release");
        if (external) {
            check(bundled.status != 0 && bundled.output.contains("Unsupported external native runtime dependency"), bundled.output);
            return;
        }
        if (dependency.contains("stub")) {
            check(bundled.status != 0 && bundled.output.contains("Test stub cannot enter"), bundled.output);
            return;
        }
        success(bundled);
        Path consumer = Files.createTempDirectory(root, "consumer-");
        Files.copy(build.resolve("fixture-only.zip"), consumer.resolve("fixture.zip"));
        success(run(consumer, "cmake", "-E", "tar", "xf", "fixture.zip"));
        Path library;
        try (var files = Files.list(consumer.resolve("lib"))) {
            library = files.filter(p -> p.getFileName().toString().contains("bridge")).findFirst().orElseThrow();
        }
        try (var files = Files.list(consumer.resolve("lib"))) {
            check(files.anyMatch(p -> p.getFileName().toString().contains(dependency)), "Runtime dependency omitted");
        }
        remove(build); // A fresh JVM can no longer resolve any producer binaries.
        Files.writeString(consumer.resolve("Consumer.java"), "class Consumer { static native int answer(); public static void main(String[] a) { System.load(a[0]); if (answer()!=42) throw new AssertionError(); } }");
        success(run(consumer, "java", "-Xcheck:jni", "Consumer.java", library.toString()));
    }
    public static void main(String[] arguments) throws Exception {
        nativeSource = Path.of(arguments[0]).toAbsolutePath();
        Path root = Files.createTempDirectory("chromellm-native-distribution-");
        try {
            refusedConfiguration(root, "linux-x86_64", true);
            refusedConfiguration(root, "linux-x86_64", false);
            for (String classifier : List.of("", "../bad", "invalid-arm64")) refusedConfiguration(root, classifier, false);
            String hostOs = System.getProperty("os.name").toLowerCase().startsWith("windows") ? "windows" : System.getProperty("os.name").equals("Mac OS X") ? "macos" : "linux";
            String hostArch = List.of("amd64", "x86_64").contains(System.getProperty("os.arch").toLowerCase()) ? "x86_64" : "arm64";
            Result noNotices = run(root, "cmake", "-S", nativeSource.toString(), "-B", Files.createTempDirectory(root, "no-notices-").toString(),
                    "-DCHROMELLM_NATIVE_DISTRIBUTION=ON", "-DCHROMELLM_NATIVE_CLASSIFIER=" + hostOs + "-" + hostArch,
                    "-DCHROMELLM_LLAMA_SOURCE_DIR=" + nativeSource);
            check(noNotices.status != 0 && noNotices.output.contains("operator-reviewed notices"), noNotices.output);
            Path cross = Files.createTempDirectory(root, "cross-");
            Result crossResult = run(root, "cmake", "-S", nativeSource.toString(), "-B", cross.toString(),
                    "-DCHROMELLM_NATIVE_DISTRIBUTION=ON", "-DCHROMELLM_NATIVE_CLASSIFIER=linux-arm64",
                    "-DCHROMELLM_LLAMA_SOURCE_DIR=" + nativeSource, "-DCMAKE_SYSTEM_NAME=Linux", "-DCMAKE_SYSTEM_PROCESSOR=arm64");
            check(crossResult.status != 0 && crossResult.output.contains("host build"), crossResult.output);
            fixture(root, "dependency", false, false);
            fixture(root, "dependency_missing", true, false);
            fixture(root, "forbidden_stub", false, false);
            if (System.getProperty("os.name").equals("Linux")) fixture(root, "dependency", false, true);
            System.out.println("Native distribution: " + checks + " checks passed");
        } finally { remove(root); }
    }
}
