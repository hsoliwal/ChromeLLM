package io.synexia.chromellm.webmcp;

import java.util.LinkedHashMap;
import java.util.Map;

/** Strict, bounded JSON object-of-strings parser for the native host's tiny request schema. */
final class JsonStrings {
    private final String input;
    private int i;

    private JsonStrings(String input) { this.input = input; }

    static Map<String, String> parse(String input) {
        JsonStrings p = new JsonStrings(input);
        Map<String, String> result = new LinkedHashMap<>();
        p.ws();
        p.expect('{');
        p.ws();
        if (!p.eat('}')) {
            do {
                if (result.size() >= 8) throw new IllegalArgumentException("too many fields");
                p.ws();
                String key = p.string();
                p.ws();
                p.expect(':');
                p.ws();
                String value = p.string();
                if (result.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException("duplicate key");
                }
                p.ws();
                if (p.eat('}')) break;
                p.expect(',');
            } while (true);
        }
        p.ws();
        if (p.i != input.length()) throw new IllegalArgumentException("trailing JSON");
        return result;
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int k = 0; k < value.length(); k++) {
            char c = value.charAt(k);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (i < input.length()) {
            char c = input.charAt(i++);
            if (c == '"') return out.toString();
            if (c == '\\') {
                if (i == input.length()) throw new IllegalArgumentException("truncated escape");
                char escape = input.charAt(i++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> out.append(hex());
                    default -> throw new IllegalArgumentException("invalid escape");
                }
            } else if (c < 0x20 || Character.isSurrogate(c)) {
                throw new IllegalArgumentException("invalid string character");
            } else out.append(c);
            if (out.length() > 1024) throw new IllegalArgumentException("string too long");
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private char hex() {
        if (i + 4 > input.length()) throw new IllegalArgumentException("invalid unicode escape");
        int v = 0;
        for (int n = 0; n < 4; n++) {
            int x = Character.digit(input.charAt(i++), 16);
            if (x < 0) throw new IllegalArgumentException("invalid unicode escape");
            v = v * 16 + x;
        }
        if (Character.isSurrogate((char) v)) {
            throw new IllegalArgumentException("surrogates not allowed in request");
        }
        return (char) v;
    }

    private void ws() {
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
            i++;
        }
    }

    private boolean eat(char c) {
        if (i < input.length() && input.charAt(i) == c) { i++; return true; }
        return false;
    }

    private void expect(char c) {
        if (!eat(c)) throw new IllegalArgumentException("invalid JSON structure");
    }
}
