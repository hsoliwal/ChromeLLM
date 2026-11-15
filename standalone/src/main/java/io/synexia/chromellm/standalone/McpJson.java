// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict bounded JSON codec for JSON-RPC messages; independent of Gemini-specific JSON. */
final class McpJson {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_VALUES = 30_000;
    private final String input;
    private int index;
    private int values;

    private McpJson(String input) { this.input = input; }

    static Object parse(String input) throws IOException {
        if (input == null) throw invalid();
        McpJson parser = new McpJson(input);
        Object value = parser.value(0);
        parser.whitespace();
        if (parser.index != input.length()) throw invalid();
        return value;
    }

    static String encode(Object value, int maxBytes) throws IOException {
        if (maxBytes < 0) throw tooLarge();
        StringBuilder out = new StringBuilder(Math.min(maxBytes, 256));
        append(out, value, 0, maxBytes);
        return out.toString();
    }

    private static int append(StringBuilder out, Object value, int depth, int remaining) throws IOException {
        if (depth > MAX_DEPTH) throw invalid();
        if (value == null) return ascii(out, "null", remaining);
        if (value instanceof String text) return quote(out, text, remaining);
        if (value instanceof Boolean || value instanceof BigDecimal || value instanceof Long
                || value instanceof Integer) {
            // BigDecimal.toString keeps large exponents compact; never expand to plain notation.
            return ascii(out, value.toString(), remaining);
        }
        if (value instanceof Map<?, ?> map) {
            remaining = ascii(out, "{", remaining);
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw invalid();
                if (!first) remaining = ascii(out, ",", remaining);
                first = false;
                remaining = quote(out, key, remaining);
                remaining = ascii(out, ":", remaining);
                remaining = append(out, entry.getValue(), depth + 1, remaining);
            }
            return ascii(out, "}", remaining);
        }
        if (value instanceof List<?> list) {
            remaining = ascii(out, "[", remaining);
            for (int i = 0; i < list.size(); i++) {
                if (i != 0) remaining = ascii(out, ",", remaining);
                remaining = append(out, list.get(i), depth + 1, remaining);
            }
            return ascii(out, "]", remaining);
        }
        throw invalid();
    }

    private static int quote(StringBuilder out, String text, int remaining) throws IOException {
        // Every UTF-16 code unit needs at least one JSON UTF-8 byte, plus the quotes.
        if (text.length() > remaining - 2) throw tooLarge();
        remaining = ascii(out, "\"", remaining);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> remaining = ascii(out, "\\\"", remaining);
                case '\\' -> remaining = ascii(out, "\\\\", remaining);
                case '\n' -> remaining = ascii(out, "\\n", remaining);
                case '\r' -> remaining = ascii(out, "\\r", remaining);
                case '\t' -> remaining = ascii(out, "\\t", remaining);
                case '\b' -> remaining = ascii(out, "\\b", remaining);
                case '\f' -> remaining = ascii(out, "\\f", remaining);
                default -> {
                    if (c < 32) {
                        if (remaining < 6) throw tooLarge();
                        remaining -= 6;
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            out.append(Character.forDigit((c >>> shift) & 15, 16));
                        }
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 == text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                            throw invalid();
                        }
                        if (remaining < 4) throw tooLarge();
                        remaining -= 4;
                        out.append(c).append(text.charAt(++i));
                    } else if (Character.isLowSurrogate(c)) {
                        throw invalid();
                    } else {
                        int width = c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
                        if (remaining < width) throw tooLarge();
                        remaining -= width;
                        out.append(c);
                    }
                }
            }
        }
        return ascii(out, "\"", remaining);
    }

    private static int ascii(StringBuilder out, String text, int remaining) throws IOException {
        if (text.length() > remaining) throw tooLarge();
        out.append(text);
        return remaining - text.length();
    }

    private static IOException tooLarge() {
        return new IOException("MCP response exceeds bounded size");
    }

    private Object value(int depth) throws IOException {
        if (depth > MAX_DEPTH || ++values > MAX_VALUES) throw invalid();
        whitespace();
        if (index == input.length()) throw invalid();
        return switch (input.charAt(index)) {
            case '{' -> object(depth + 1);
            case '[' -> array(depth + 1);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) throws IOException {
        index++;
        Map<String, Object> values = new LinkedHashMap<>();
        whitespace();
        if (take('}')) return values;
        do {
            whitespace();
            String key = string();
            whitespace();
            if (!take(':') || values.containsKey(key)) throw invalid();
            values.put(key, value(depth));
            whitespace();
            if (take('}')) return values;
        } while (take(','));
        throw invalid();
    }

    private List<Object> array(int depth) throws IOException {
        index++;
        List<Object> result = new ArrayList<>();
        whitespace();
        if (take(']')) return result;
        do {
            result.add(value(depth));
            whitespace();
            if (take(']')) return result;
        } while (take(','));
        throw invalid();
    }

    private String string() throws IOException {
        if (!take('"')) throw invalid();
        StringBuilder text = new StringBuilder();
        while (index < input.length()) {
            char c = input.charAt(index++);
            if (c == '"') {
                for (int i = 0; i < text.length(); i++) {
                    char at = text.charAt(i);
                    if (Character.isHighSurrogate(at)) {
                        if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid();
                    } else if (Character.isLowSurrogate(at)) throw invalid();
                }
                return text.toString();
            }
            if (c < 32) throw invalid();
            if (c != '\\') { text.append(c); continue; }
            if (index == input.length()) throw invalid();
            switch (input.charAt(index++)) {
                case '"', '\\', '/' -> text.append(input.charAt(index - 1));
                case 'b' -> text.append('\b');
                case 'f' -> text.append('\f');
                case 'n' -> text.append('\n');
                case 'r' -> text.append('\r');
                case 't' -> text.append('\t');
                case 'u' -> {
                    if (input.length() - index < 4) throw invalid();
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        char digit = input.charAt(index++);
                        int d = digit >= '0' && digit <= '9' ? digit - '0'
                                : digit >= 'a' && digit <= 'f' ? digit - 'a' + 10
                                : digit >= 'A' && digit <= 'F' ? digit - 'A' + 10 : -1;
                        if (d < 0) throw invalid();
                        code = (code << 4) | d;
                    }
                    text.append((char) code);
                }
                default -> throw invalid();
            }
        }
        throw invalid();
    }

    private Object number() throws IOException {
        int start = index;
        take('-');
        if (!take('0')) {
            if (index == input.length() || input.charAt(index) < '1' || input.charAt(index) > '9') throw invalid();
            digits();
        }
        if (take('.')) requiredDigits();
        if (take('e') || take('E')) {
            if (!take('+')) take('-');
            requiredDigits();
        }
        try { return new BigDecimal(input.substring(start, index)); }
        catch (NumberFormatException bad) { throw invalid(); }
    }

    private void requiredDigits() throws IOException {
        int start = index;
        digits();
        if (start == index) throw invalid();
    }
    private void digits() {
        while (index < input.length() && input.charAt(index) >= '0' && input.charAt(index) <= '9') index++;
    }
    private Object literal(String token, Object result) throws IOException {
        if (!input.startsWith(token, index)) throw invalid();
        index += token.length();
        return result;
    }
    private boolean take(char c) {
        if (index < input.length() && input.charAt(index) == c) { index++; return true; }
        return false;
    }
    private void whitespace() {
        while (index < input.length()) {
            char c = input.charAt(index);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
            index++;
        }
    }
    private static IOException invalid() { return new IOException("MCP JSON is invalid or too complex"); }
}
