package movies.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader - enough for the REST APIs MovieTool talks to, with
 * no external dependencies (Java 8).
 *
 * <p>{@link #parse} returns nested {@link Map} (objects), {@link List}
 * (arrays), {@link String}, {@link Long}/{@link Double} (numbers) or
 * {@code null}. Invalid input raises {@link JsonException}.</p>
 */
public final class Json {

    public static class JsonException extends RuntimeException {
        JsonException(String message) { super(message); }
    }

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos < parser.text.length()) {
            throw new JsonException("unexpected trailing content at " + parser.pos);
        }
        return value;
    }

    /** Escapes a string for embedding in a JSON request body. */
    public static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Convenience: top-level object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new JsonException("expected a JSON object");
        return (Map<String, Object>) value;
    }

    private Object readValue() {
        if (pos >= text.length()) throw new JsonException("unexpected end of input");
        char c = text.charAt(pos);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default:  return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        pos++; // {
        skipWhitespace();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw new JsonException("expected a key at " + pos);
            String key = readString();
            skipWhitespace();
            if (peek() != ':') throw new JsonException("expected ':' at " + pos);
            pos++;
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; return map; }
            throw new JsonException("expected ',' or '}' at " + pos);
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<Object>();
        pos++; // [
        skipWhitespace();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; return list; }
            throw new JsonException("expected ',' or ']' at " + pos);
        }
    }

    private String readString() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) throw new JsonException("unterminated string");
            char c = text.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= text.length()) throw new JsonException("unterminated escape");
                char e = text.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > text.length()) throw new JsonException("bad \\u escape");
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: throw new JsonException("unknown escape \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                pos++;
            } else {
                break;
            }
        }
        String token = text.substring(start, pos);
        if (token.isEmpty()) throw new JsonException("invalid number at " + start);
        try {
            if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                return Long.valueOf(token);
            }
            return Double.valueOf(token);
        } catch (NumberFormatException e) {
            throw new JsonException("invalid number '" + token + "'");
        }
    }

    private void expect(String word) {
        if (!text.regionMatches(pos, word, 0, word.length())) {
            throw new JsonException("expected '" + word + "' at " + pos);
        }
        pos += word.length();
    }

    private char peek() {
        if (pos >= text.length()) throw new JsonException("unexpected end of input");
        return text.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++; else return;
        }
    }
}
