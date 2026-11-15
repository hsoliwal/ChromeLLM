// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Synthetic argv-compatible child; not a model or inference-quality test. */
public final class FakeLlamaCppMain {
    private FakeLlamaCppMain() { }

    public static void main(String[] args) throws Exception {
        List<String> a = List.of(args);
        if (a.contains("--prompt-file") || !a.contains("--no-display-prompt")) {
            System.exit(7);
        }
        int index = a.indexOf("-f");
        if (index < 0 || index + 1 >= a.size() || a.indexOf("-m") < 0 || a.indexOf("-n") < 0) {
            System.exit(8);
        }
        String prompt = Files.readString(Path.of(a.get(index + 1)));
        if (a.contains(prompt)) System.exit(9); // Prompt must never be placed on argv.
        int readyIndex = a.indexOf("--ready-file");
        if (readyIndex >= 0) {
            Files.writeString(Path.of(a.get(readyIndex + 1)), ProcessHandle.current().pid() + "\n");
        }
        if (prompt.equals("sleep")) Thread.sleep(readyIndex >= 0 ? 60_000 : 3000);
        if (prompt.startsWith("cleanup-failure")) {
            Path promptFile = Path.of(a.get(index + 1));
            Files.delete(promptFile);
            Files.createDirectory(promptFile);
            Files.writeString(promptFile.resolve("owned-marker"), "force a cleanup refusal");
            if (prompt.equals("cleanup-failure-exit")) System.exit(4);
        }
        if (prompt.equals("fail")) System.exit(4);
        System.out.print("synthetic reply");
    }
}
