package io.synexia.chromellm.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CubeLutParser {
    public CubeLut parse(Path path) throws IOException {
        int size = -1;
        float[] domainMin = {0f, 0f, 0f};
        float[] domainMax = {1f, 1f, 1f};
        List<Float> values = new ArrayList<>();

        int lineNumber = 0;
        for (String sourceLine : Files.readAllLines(path)) {
            lineNumber++;
            String line = stripComment(sourceLine).trim();
            if (line.isEmpty()) continue;

            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("TITLE ")) continue;
            if (upper.startsWith("LUT_1D_SIZE") || upper.startsWith("LUT_1D_INPUT_RANGE")) {
                throw error(path, lineNumber, "1D/shaper LUT sections are not supported");
            }
            if (upper.startsWith("LUT_3D_SIZE")) {
                size = Integer.parseInt(tokens(line)[1]);
                continue;
            }
            if (upper.startsWith("DOMAIN_MIN")) {
                domainMin = vector(tokens(line), path, lineNumber);
                continue;
            }
            if (upper.startsWith("DOMAIN_MAX")) {
                domainMax = vector(tokens(line), path, lineNumber);
                continue;
            }
            if (upper.startsWith("LUT_3D_INPUT_RANGE")) {
                String[] parts = tokens(line);
                if (parts.length != 3) throw error(path, lineNumber, "LUT_3D_INPUT_RANGE requires min and max");
                float min = Float.parseFloat(parts[1]);
                float max = Float.parseFloat(parts[2]);
                domainMin = new float[]{min, min, min};
                domainMax = new float[]{max, max, max};
                continue;
            }

            String[] parts = tokens(line);
            if (parts.length != 3) throw error(path, lineNumber, "expected RGB triplet");
            for (String part : parts) {
                float value = Float.parseFloat(part);
                if (!Float.isFinite(value)) throw error(path, lineNumber, "LUT values must be finite");
                values.add(value);
            }
        }

        if (size < 0) throw new IllegalArgumentException(path + ": missing LUT_3D_SIZE");
        int expected = Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(size, size), size), 3);
        if (values.size() != expected) {
            throw new IllegalArgumentException(path + ": expected " + expected + " LUT values but found " + values.size());
        }

        float[] table = new float[values.size()];
        for (int i = 0; i < table.length; i++) table[i] = values.get(i);
        return new CubeLut(size, domainMin, domainMax, table);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static String[] tokens(String line) {
        return line.trim().split("\\s+");
    }

    private static float[] vector(String[] parts, Path path, int lineNumber) {
        if (parts.length != 4) throw error(path, lineNumber, "domain line requires 3 values");
        return new float[]{
                Float.parseFloat(parts[1]),
                Float.parseFloat(parts[2]),
                Float.parseFloat(parts[3])
        };
    }

    private static IllegalArgumentException error(Path path, int lineNumber, String message) {
        return new IllegalArgumentException(path + ":" + lineNumber + ": " + message);
    }
}
