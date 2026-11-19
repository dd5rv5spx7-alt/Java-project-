package com.agrocti.util;

import java.util.Collection;
import java.util.Locale;

/** A very small JSON writer, so that the project needs no JSON library. */
public final class Json {

    private Json() { }

    /** Implemented by every class that can be sent to the dashboard. */
    public interface Writable {
        String toJson();
    }

    public static Builder object() {
        return new Builder();
    }

    public static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    public static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "null";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return String.format(Locale.ROOT, "%.6f", d);
    }

    /** Writes any collection of Writable objects (upper-bounded wildcard). */
    public static String array(Collection<? extends Writable> items) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Writable w : items) {
            if (!first) {
                sb.append(',');
            }
            sb.append(w.toJson());
            first = false;
        }
        return sb.append(']').toString();
    }

    public static String numbers(double[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(num(values[i]));
        }
        return sb.append(']').toString();
    }

    public static String strings(Collection<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) {
                sb.append(',');
            }
            sb.append(quote(v));
            first = false;
        }
        return sb.append(']').toString();
    }

    public static final class Builder {
        private final StringBuilder sb = new StringBuilder("{");
        private boolean first = true;

        private Builder key(String k) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(k)).append(':');
            return this;
        }

        public Builder put(String k, String v) { key(k).sb.append(quote(v)); return this; }
        public Builder put(String k, long v) { key(k).sb.append(v); return this; }
        public Builder put(String k, double v) { key(k).sb.append(num(v)); return this; }
        public Builder put(String k, boolean v) { key(k).sb.append(v); return this; }
        public Builder raw(String k, String json) { key(k).sb.append(json); return this; }

        public String build() {
            return sb.toString() + "}";
        }
    }
}
