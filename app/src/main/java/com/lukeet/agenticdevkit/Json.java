package com.lukeet.agenticdevkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny dependency-free JSON writer/parser. Only what the kit needs:
 * objects, arrays, strings, numbers, booleans, null.
 */
public final class Json {

    private Json() {}

    /* ------------------------------------------------------------------ write */

    public static String esc(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    public static String str(String s) {
        return s == null ? "null" : "\"" + esc(s) + "\"";
    }

    /** Fluent object builder: new Json.Obj().put("a", 1).put("b", "x").toString() */
    public static final class Obj {
        // starts with the opening brace: everything here appends key/value pairs
        private final StringBuilder sb = new StringBuilder(128).append('{');
        private boolean first = true;

        private Obj key(String k) {
            if (!first) sb.append(',');
            first = false;
            sb.append(str(k)).append(':');
            return this;
        }

        public Obj put(String k, String v) {
            key(k);
            sb.append(str(v));
            return this;
        }

        public Obj put(String k, long v) {
            key(k);
            sb.append(v);
            return this;
        }

        public Obj put(String k, int v) {
            return put(k, (long) v);
        }

        public Obj put(String k, boolean v) {
            key(k);
            sb.append(v);
            return this;
        }

        public Obj put(String k, double v) {
            key(k);
            if (Double.isNaN(v) || Double.isInfinite(v)) sb.append("null");
            else sb.append(v);
            return this;
        }

        public Obj put(String k, Obj v) {
            key(k);
            sb.append(v == null ? "null" : v.toString());
            return this;
        }

        public Obj putRaw(String k, String rawJson) {
            key(k);
            sb.append(rawJson == null ? "null" : rawJson);
            return this;
        }

        /** Array of already-encoded JSON values. */
        public Obj putRawArray(String k, List<String> rawItems) {
            key(k);
            sb.append(arr(rawItems));
            return this;
        }

        /** Array of plain strings. */
        public Obj putStrings(String k, List<String> items) {
            List<String> raw = new ArrayList<>();
            if (items != null) for (String s : items) raw.add(str(s));
            return putRawArray(k, raw);
        }

        @Override
        public String toString() {
            return sb.toString() + "}";
        }
    }

    public static String arr(List<String> rawItems) {
        StringBuilder b = new StringBuilder("[");
        if (rawItems != null) {
            for (int i = 0; i < rawItems.size(); i++) {
                if (i > 0) b.append(',');
                b.append(rawItems.get(i));
            }
        }
        return b.append(']').toString();
    }

    /* ------------------------------------------------------------------ parse */

    public static Object parse(String s) {
        if (s == null) return null;
        Parser p = new Parser(s);
        p.skipWs();
        if (p.eof()) return null;
        Object v = p.value();
        p.skipWs();
        return v;
    }

    /** Parse a JSON object body, returning an empty map on anything unexpected. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObj(String s) {
        Object o = parse(s);
        if (o instanceof Map) return (Map<String, Object>) o;
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArr(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    public static String s(Map<String, Object> m, String k, String def) {
        Object v = m == null ? null : m.get(k);
        if (v == null) return def;
        if (v instanceof String) return (String) v;
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return String.valueOf(v);
    }

    public static long i(Map<String, Object> m, String k, long def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Double) return (long) (double) (Double) v;
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); } catch (Exception ignored) { }
        }
        return def;
    }

    public static double d(Map<String, Object> m, String k, double def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Double) return (Double) v;
        if (v instanceof String) {
            try { return Double.parseDouble(((String) v).trim()); } catch (Exception ignored) { }
        }
        return def;
    }

    public static boolean b(Map<String, Object> m, String k, boolean def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Double) return ((Double) v) != 0d;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes")) return true;
            if (s.equalsIgnoreCase("false") || s.equals("0") || s.equalsIgnoreCase("no")) return false;
        }
        return def;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        boolean eof() { return i >= s.length(); }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                else break;
            }
        }

        Object value() {
            skipWs();
            if (eof()) return null;
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't':
                    if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
                    throw err();
                case 'f':
                    if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
                    throw err();
                case 'n':
                    if (s.startsWith("null", i)) { i += 4; return null; }
                    throw err();
                default: return number();
            }
        }

        private RuntimeException err() {
            return new IllegalArgumentException("bad json at " + i);
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (!eof() && s.charAt(i) == '}') { i++; return m; }
            while (!eof()) {
                skipWs();
                String k = string();
                skipWs();
                if (eof() || s.charAt(i) != ':') throw err();
                i++;
                Object v = value();
                m.put(k, v);
                skipWs();
                if (eof()) throw err();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err();
            }
            throw err();
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; // [
            skipWs();
            if (!eof() && s.charAt(i) == ']') { i++; return l; }
            while (!eof()) {
                l.add(value());
                skipWs();
                if (eof()) throw err();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw err();
            }
            throw err();
        }

        private String string() {
            if (eof() || s.charAt(i) != '"') throw err();
            i++;
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (eof()) throw err();
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw err();
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw err();
                    }
                } else {
                    b.append(c);
                }
            }
            throw err();
        }

        private Object number() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
                else break;
            }
            if (start == i) throw err();
            return Double.parseDouble(s.substring(start, i));
        }
    }
}
