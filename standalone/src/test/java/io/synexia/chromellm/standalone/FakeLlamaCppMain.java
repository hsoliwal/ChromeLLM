// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
        if (prompt.equals("check-webgpu")) {
            int device = a.indexOf("--device");
            int layers = a.indexOf("-ngl");
            if (device < 0 || device + 1 >= a.size() || !a.get(device + 1).equals("WebGPU0")
                    || layers < 0 || layers + 1 >= a.size() || !a.get(layers + 1).equals("16")) {
                System.exit(12);
            }
        }
        int readyIndex = a.indexOf("--ready-file");
        if (readyIndex >= 0) {
            Files.writeString(Path.of(a.get(readyIndex + 1)), ProcessHandle.current().pid() + "\n");
        }
        if (prompt.equals("sleep")) Thread.sleep(readyIndex >= 0 ? 60_000 : 3000);
        if (prompt.equals("stdout-limit") || prompt.equals("stdout-overflow")) {
            byte[] chunk = new byte[8192];
            Arrays.fill(chunk, (byte) 'x');
            for (int i = 0; i < 512; i++) System.out.write(chunk);
            if (prompt.equals("stdout-overflow")) {
                System.out.write('x');
                System.out.flush();
                Thread.sleep(60_000); // The receiver must refuse the excess while we are still alive.
            }
            return;
        }
        if (prompt.equals("stderr-flood")) {
            byte[] chunk = new byte[8192];
            Arrays.fill(chunk, (byte) 'e');
            for (int i = 0; i < 1024; i++) System.err.write(chunk);
        }
        if (prompt.equals("stdin-eof") && System.in.read() != -1) System.exit(14);
        if (prompt.equals("empty-output")) return;
        if (prompt.equals("invalid-output")) {
            System.out.write(new byte[]{(byte) 0xc0, (byte) 0x80});
            return;
        }
        if (prompt.equals("truncated-output")) {
            System.out.write(new byte[]{(byte) 0xf0, (byte) 0x9f, (byte) 0xa6});
            return;
        }
        if (prompt.equals("unicode-output")) {
            byte[] response = "नमस्ते 🦙".getBytes(StandardCharsets.UTF_8);
            for (byte value : response) {
                System.out.write(value);
                System.out.flush();
                Thread.sleep(2); // Deliberately split a multibyte code point across reads.
            }
            return;
        }
        if (prompt.equals("stream-then-sleep")) {
            System.out.print("partial output");
            System.out.flush();
            Thread.sleep(60_000);
        }
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
