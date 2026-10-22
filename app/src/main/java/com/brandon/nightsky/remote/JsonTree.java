package com.brandon.nightsky.remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader: {@link #parse} turns text into maps (insertion order kept), lists, strings, doubles,
 * booleans and nulls; {@link Obj} reads fields with defaults, so a file written by an older or newer version
 * still loads.
 */
public final class JsonTree {
    private final String s;
    private int i;

    private JsonTree(String s) {
        this.s = s;
    }

    /** The value the text holds; throws IllegalArgumentException if it isn't JSON. */
    public static Object parse(String text) {
        JsonTree t = new JsonTree(text);
        t.ws();
        Object v = t.value(0);
        t.ws();
        if (t.i < t.s.length()) throw new IllegalArgumentException("unexpected text at " + t.i);
        return v;
    }

    /** The object the text holds; throws IllegalArgumentException if it isn't a JSON object. */
    public static Obj object(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("not a JSON object");
        return new Obj(cast(v));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object v) {
        return (Map<String, Object>) v;
    }

    private Object value(int depth) {
        if (depth > 64) throw new IllegalArgumentException("nested too deep");
        char c = peek();
        switch (c) {
            case '{': {
                i++;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                ws();
                if (peek() == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    ws();
                    m.put(k, value(depth + 1));
                    ws();
                    char d = next();
                    if (d == '}') return m;
                    if (d != ',') throw new IllegalArgumentException("expected , or } at " + (i - 1));
                }
            }
            case '[': {
                i++;
                List<Object> l = new ArrayList<Object>();
                ws();
                if (peek() == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    ws();
                    l.add(value(depth + 1));
                    ws();
                    char d = next();
                    if (d == ']') return l;
                    if (d != ',') throw new IllegalArgumentException("expected , or ] at " + (i - 1));
                }
            }
            case '"':
                return string();
            default:
                if (s.startsWith("true", i)) {
                    i += 4;
                    return Boolean.TRUE;
                }
                if (s.startsWith("false", i)) {
                    i += 5;
                    return Boolean.FALSE;
                }
                if (s.startsWith("null", i)) {
                    i += 4;
                    return null;
                }
                int a = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                if (a == i) throw new IllegalArgumentException("unexpected " + c + " at " + i);
                try {
                    return Double.parseDouble(s.substring(a, i));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("bad number at " + a);
                }
        }
    }

    private String string() {
        if (next() != '"') throw new IllegalArgumentException("expected a string at " + (i - 1));
        StringBuilder b = null;
        int a = i;
        while (true) {
            char c = next();
            if (c == '"') return b == null ? s.substring(a, i - 1) : b.toString();
            if (c != '\\') {
                if (b != null) b.append(c);
                continue;
            }
            if (b == null) b = new StringBuilder(s.substring(a, i - 1));
            char e = next();
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (i + 4 > s.length()) throw new IllegalArgumentException("bad escape at " + i);
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException x) {
                        throw new IllegalArgumentException("bad escape at " + i);
                    }
                    i += 4;
                    break;
                default: b.append(e);
            }
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private char next() {
        if (i >= s.length()) throw new IllegalArgumentException("the text ends too soon");
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) throw new IllegalArgumentException("expected " + c + " at " + (i - 1));
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    /** A JSON object with forgiving getters: a missing or mistyped field gives the default. */
    public static final class Obj {
        public final Map<String, Object> map;

        public Obj(Map<String, Object> map) {
            this.map = map;
        }

        public boolean has(String k) {
            return map.containsKey(k) && map.get(k) != null;
        }

        public String str(String k, String def) {
            Object v = map.get(k);
            return v instanceof String ? (String) v : v instanceof Double || v instanceof Boolean ? v.toString() : def;
        }

        public double num(String k, double def) {
            Object v = map.get(k);
            if (v instanceof Double) return (Double) v;
            if (v instanceof String) {
                try {
                    return Double.parseDouble(((String) v).trim());
                } catch (NumberFormatException e) {
                    return def;
                }
            }
            return def;
        }

        public long lng(String k, long def) {
            double d = num(k, Double.NaN);
            return Double.isNaN(d) ? def : Math.round(d);
        }

        public int integer(String k, int def) {
            double d = num(k, Double.NaN);
            return Double.isNaN(d) ? def : (int) Math.round(d);
        }

        public boolean bool(String k, boolean def) {
            Object v = map.get(k);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String) return "true".equalsIgnoreCase((String) v);
            return def;
        }

        /** The object under {@code k}, or null. */
        public Obj obj(String k) {
            Object v = map.get(k);
            return v instanceof Map ? new Obj(cast(v)) : null;
        }

        /** The list under {@code k} (empty if none). */
        @SuppressWarnings("unchecked")
        public List<Object> list(String k) {
            Object v = map.get(k);
            return v instanceof List ? (List<Object>) v : Collections.emptyList();
        }

        /** The objects in the list under {@code k} (other entries skipped). */
        public List<Obj> objs(String k) {
            List<Obj> out = new ArrayList<Obj>();
            for (Object o : list(k)) if (o instanceof Map) out.add(new Obj(cast(o)));
            return out;
        }

        /** The numbers in the list under {@code k}; nulls and others become NaN. */
        public double[] nums(String k) {
            List<Object> l = list(k);
            double[] out = new double[l.size()];
            for (int j = 0; j < out.length; j++) {
                Object o = l.get(j);
                out[j] = o instanceof Double ? (Double) o : Double.NaN;
            }
            return out;
        }

        /** The strings in the list under {@code k} (others skipped). */
        public List<String> strs(String k) {
            List<String> out = new ArrayList<String>();
            for (Object o : list(k)) if (o instanceof String) out.add((String) o);
            return out;
        }
    }
}
