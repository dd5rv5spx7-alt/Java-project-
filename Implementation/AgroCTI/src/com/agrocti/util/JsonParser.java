package com.agrocti.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small recursive-descent JSON reader used for the threat-intelligence feed.
 * Objects become Map&lt;String,Object&gt;, arrays become List&lt;Object&gt;,
 * numbers become Double.
 */
public final class JsonParser {

    private final String s;
    private int pos;

    private JsonParser(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        JsonParser p = new JsonParser(text);
        p.skip();
        Object v = p.value();
        p.skip();
        if (p.pos != p.s.length()) {
            throw p.error("unexpected text after the end");
        }
        return v;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("JSON error at character " + pos + ": " + msg);
    }

    private void skip() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
    }

    private Object value() {
        if (pos >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(pos);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
        if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
        if (s.startsWith("null", pos)) { pos += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skip();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            skip();
            if (peek() != '"') throw error("expected a key");
            String k = string();
            skip();
            expect(':');
            skip();
            map.put(k, value());
            skip();
            if (peek() == ',') { pos++; continue; }
            expect('}');
            return map;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++;
        skip();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            skip();
            list.add(value());
            skip();
            if (peek() == ',') { pos++; continue; }
            expect(']');
            return list;
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        pos++;
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16)); pos += 4; break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw error("unterminated string");
    }

    private Double number() {
        int start = pos;
        while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) throw error("unexpected character '" + s.charAt(pos) + "'");
        try {
            return Double.valueOf(s.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private char peek() {
        if (pos >= s.length()) throw error("unexpected end");
        return s.charAt(pos);
    }

    private void expect(char c) {
        if (peek() != c) throw error("expected '" + c + "'");
        pos++;
    }
}
