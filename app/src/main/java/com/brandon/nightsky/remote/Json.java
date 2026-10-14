package com.brandon.nightsky.remote;

import java.util.Locale;

/** Minimal JSON writer: {@code new Json().obj().put("a", 1).arr("b").val(2).end().end().toString()}. */
public final class Json {
    private final StringBuilder sb = new StringBuilder(256);
    /** Per nesting level: its closing bracket, and whether it has an element yet (a comma goes before the next). */
    private final char[] close = new char[32];
    private final boolean[] used = new boolean[32];
    private int depth;
    private boolean afterName;

    public Json obj() {
        open('{', '}');
        return this;
    }

    public Json obj(String name) {
        name(name);
        return obj();
    }

    public Json arr() {
        open('[', ']');
        return this;
    }

    public Json arr(String name) {
        name(name);
        return arr();
    }

    public Json end() {
        sb.append(close[depth]);
        depth--;
        return this;
    }

    private void open(char c, char closing) {
        sep();
        sb.append(c);
        depth++;
        close[depth] = closing;
        used[depth] = false;
    }

    private void sep() {
        if (afterName) {
            afterName = false;
            return;
        }
        if (depth > 0) {
            if (used[depth]) sb.append(',');
            used[depth] = true;
        }
    }

    private void name(String n) {
        sep();
        str(n);
        sb.append(':');
        afterName = true;
    }

    public Json put(String name, String v) {
        name(name);
        return val(v);
    }

    public Json put(String name, long v) {
        name(name);
        return val(v);
    }

    public Json put(String name, double v) {
        name(name);
        return val(v);
    }

    public Json put(String name, boolean v) {
        name(name);
        return val(v);
    }

    /** Inserts already-encoded JSON as the value of {@code name}. */
    public Json raw(String name, String json) {
        name(name);
        sep();
        sb.append(json);
        return this;
    }

    public Json val(String v) {
        sep();
        if (v == null) sb.append("null");
        else str(v);
        return this;
    }

    public Json val(long v) {
        sep();
        sb.append(v);
        return this;
    }

    public Json val(double v) {
        sep();
        if (Double.isNaN(v) || Double.isInfinite(v)) sb.append("null");
        else if (v == Math.rint(v) && Math.abs(v) < 1e15) sb.append((long) v);
        else sb.append(String.format(Locale.US, "%.4g", v));
        return this;
    }

    public Json val(boolean v) {
        sep();
        sb.append(v);
        return this;
    }

    private void str(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break; // safe inside HTML too
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
