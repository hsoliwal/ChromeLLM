// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Verify the exact, locally supplied native source revision before CMake compiles it. */
public final class ForkSourcePin {
    private ForkSourcePin() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("expected native source path and full commit SHA");
        verify(Path.of(args[0]), args[1]);
    }

    static void verify(Path source, String expectedCommit) throws Exception {
        if (expectedCommit == null || !expectedCommit.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("native source must have a complete 40-hex commit pin");
        }
        Path root = source.toRealPath();
        if (!Files.isRegularFile(root.resolve("CMakeLists.txt"))
                || !Files.isRegularFile(root.resolve("include/llama.h"))
                || !Files.isRegularFile(root.resolve("ggml/include/ggml-backend.h"))) {
            throw new IOException("fork is not a compatible llama.cpp source checkout");
        }
        String repoRoot = git(root, "rev-parse", "--show-toplevel");
        if (!Path.of(repoRoot).toRealPath().equals(root)) {
            throw new IOException("fork source must be the exact Git worktree root");
        }
        String actual = git(root, "rev-parse", "--verify", "HEAD");
        if (!expectedCommit.equalsIgnoreCase(actual)) {
            throw new IOException("fork commit does not match the pinned source revision");
        }
        if (!git(root, "status", "--porcelain", "--untracked-files=no").isEmpty()) {
            throw new IOException("fork has modified tracked files; native build refused");
        }
        System.out.println("verified native fork commit: " + actual);
    }

    private static String git(Path root, String... commands) throws Exception {
        var args = new java.util.ArrayList<>(List.of("git", "-C", root.toString()));
        args.addAll(List.of(commands));
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            byte[] output = process.getInputStream().readNBytes(65537);
            if (output.length > 65536 || !process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("cannot verify local native fork with Git");
            }
            return new String(output, StandardCharsets.UTF_8).strip();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            process.getInputStream().close();
            process.getOutputStream().close();
            process.getErrorStream().close();
        }
    }
}
