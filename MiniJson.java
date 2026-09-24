package dev.maiui.mvp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny order-preserving JSON reader (used only to remember the model's argument key order). */
final class MiniJson {
    private final String s; private int i;
    private MiniJson(String s) { this.s = s; }

    static Object parse(String s) {
        MiniJson p = new MiniJson(s); p.ws(); Object v = p.value(); p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("Trailing data at " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    static List<String> argumentKeyOrder(String toolCallJson) {
        try {
            Object o = parse(toolCallJson);
            if (o instanceof Map) {
                Object a = ((Map<String, Object>) o).get("arguments");
                if (a instanceof Map) return new ArrayList<>(((Map<String, Object>) a).keySet());
                if (o != null && ((Map<String, Object>) o).containsKey("action")) return new ArrayList<>(((Map<String, Object>) o).keySet());
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object value() {
        if (i >= s.length()) throw new IllegalArgumentException("Unexpected end");
        char c = s.charAt(i);
        if (c == '{') {
            i++; Map<String, Object> m = new LinkedHashMap<>(); ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = str(); ws(); expect(':'); ws(); m.put(k, value()); ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect('}'); return m;
            }
        }
        if (c == '[') {
            i++; List<Object> l = new ArrayList<>(); ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws(); l.add(value()); ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect(']'); return l;
            }
        }
        if (c == '"') return str();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw new IllegalArgumentException("Bad token at " + i);
        return Double.parseDouble(s.substring(st, i));
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("Expected " + c + " at " + i);
        i++;
    }

    private String str() {
        expect('"'); StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            } else b.append(c);
        }
    }
}
