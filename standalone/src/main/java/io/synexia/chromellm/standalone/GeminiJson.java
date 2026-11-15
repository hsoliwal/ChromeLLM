// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Bounded JSON transport codec for the text-only Gemini adapter; not a public JSON API. */
final class GeminiJson {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_VALUES = 100_000;
    private final String source;
    private int offset;
    private int values;

    private GeminiJson(String source) {
        this.source = source;
    }

    static Object parse(String source) throws IOException {
        GeminiJson parser = new GeminiJson(source);
        Object result = parser.value(0);
        parser.space();
        if (parser.offset != source.length()) throw invalid();
        return result;
    }

    static String quote(String value, int maxBytes) throws IOException {
        checkQuotedSize(value, maxBytes);
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 32) {
                        quoted.append("\\u00").append(Character.forDigit(c >>> 4, 16))
                                .append(Character.forDigit(c & 15, 16));
                    } else if (Character.isHighSurrogate(c)) {
                        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                            throw new IOException("Gemini request contains invalid Unicode");
                        }
                        quoted.append(c).append(value.charAt(i));
                    } else if (Character.isLowSurrogate(c)) {
                        throw new IOException("Gemini request contains invalid Unicode");
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    /** The escaped representation is never smaller than the input UTF-8 representation. */
    private static void checkQuotedSize(String value, int maxBytes) throws IOException {
        if (maxBytes < 2 || value.length() > maxBytes - 2) {
            throw new IOException("Gemini request exceeds the bounded UTF-8/JSON limit");
        }
        int bytes = 2;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\' || c == '\b' || c == '\f' || c == '\n' || c == '\r' || c == '\t') {
                bytes += 2;
            } else if (c < 32) {
                bytes += 6;
            } else if (c < 128) {
                bytes++;
            } else if (c < 2048) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IOException("Gemini request contains invalid Unicode");
                }
                bytes += 4;
            } else if (Character.isLowSurrogate(c)) {
                throw new IOException("Gemini request contains invalid Unicode");
            } else {
                bytes += 3;
            }
            if (bytes > maxBytes) throw new IOException("Gemini request exceeds the bounded UTF-8/JSON limit");
        }
    }

    private Object value(int depth) throws IOException {
        if (depth > MAX_DEPTH || ++values > MAX_VALUES) throw invalid();
        space();
        if (offset == source.length()) throw invalid();
        return switch (source.charAt(offset)) {
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
        offset++;
        Map<String, Object> object = new HashMap<>();
        space();
        if (take('}')) return object;
        do {
            space();
            String key = string();
            space();
            if (!take(':') || object.containsKey(key)) throw invalid();
            object.put(key, value(depth));
            space();
            if (take('}')) return object;
        } while (take(','));
        throw invalid();
    }

    private ArrayList<Object> array(int depth) throws IOException {
        offset++;
        ArrayList<Object> array = new ArrayList<>();
        space();
        if (take(']')) return array;
        do {
            array.add(value(depth));
            space();
            if (take(']')) return array;
        } while (take(','));
        throw invalid();
    }

    private String string() throws IOException {
        if (!take('"')) throw invalid();
        StringBuilder text = new StringBuilder();
        while (offset < source.length()) {
            char c = source.charAt(offset++);
            if (c == '"') {
                validateUnicode(text);
                return text.toString();
            }
            if (c < 32) throw invalid();
            if (c != '\\') {
                text.append(c);
                continue;
            }
            if (offset == source.length()) throw invalid();
            char escaped = source.charAt(offset++);
            switch (escaped) {
                case '"', '\\', '/' -> text.append(escaped);
                case 'b' -> text.append('\b');
                case 'f' -> text.append('\f');
                case 'n' -> text.append('\n');
                case 'r' -> text.append('\r');
                case 't' -> text.append('\t');
                case 'u' -> text.append(unicodeEscape());
                default -> throw invalid();
            }
        }
        throw invalid();
    }

    private char unicodeEscape() throws IOException {
        if (source.length() - offset < 4) throw invalid();
        int code = 0;
        for (int i = 0; i < 4; i++) {
            char c = source.charAt(offset++);
            int digit = c >= '0' && c <= '9' ? c - '0'
                    : c >= 'a' && c <= 'f' ? c - 'a' + 10
                    : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (digit < 0) throw invalid();
            code = (code << 4) | digit;
        }
        return (char) code;
    }

    private static void validateUnicode(CharSequence text) throws IOException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) {
                throw invalid();
            }
        }
    }

    private Object literal(String expected, Object value) throws IOException {
        if (!source.startsWith(expected, offset)) throw invalid();
        offset += expected.length();
        return value;
    }

    private Object number() throws IOException {
        take('-');
        if (!take('0')) {
            if (offset == source.length() || source.charAt(offset) < '1' || source.charAt(offset) > '9') {
                throw invalid();
            }
            digits();
        }
        if (take('.')) requiredDigits();
        if (take('e') || take('E')) {
            if (!take('+')) take('-');
            requiredDigits();
        }
        // Numeric metadata is syntactically checked but never converted or exposed by this adapter.
        return NumberValue.INSTANCE;
    }

    private void requiredDigits() throws IOException {
        int start = offset;
        digits();
        if (start == offset) throw invalid();
    }

    private void digits() {
        while (offset < source.length() && source.charAt(offset) >= '0' && source.charAt(offset) <= '9') offset++;
    }

    private boolean take(char expected) {
        if (offset < source.length() && source.charAt(offset) == expected) {
            offset++;
            return true;
        }
        return false;
    }

    private void space() {
        while (offset < source.length()) {
            char c = source.charAt(offset);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
            offset++;
        }
    }

    private static IOException invalid() {
        return new IOException("Gemini response contains invalid or excessively complex JSON");
    }

    private enum NumberValue { INSTANCE }
}
