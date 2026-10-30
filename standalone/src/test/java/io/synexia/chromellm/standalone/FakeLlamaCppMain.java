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
        if (prompt.equals("sleep")) Thread.sleep(3000);
        if (prompt.equals("fail")) System.exit(4);
        System.out.print("synthetic reply");
    }
}
