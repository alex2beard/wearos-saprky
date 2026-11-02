package ru.wearsleep.lab;

import java.util.List;

public final class Csv {
    private Csv() {}

    public static String field(Object value) {
        String text = value == null ? "" : value.toString();
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    public static String line(Object... values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i != 0) out.append(',');
            out.append(field(values[i]));
        }
        return out.append('\n').toString();
    }

    public static String list(List<Integer> values) {
        if (values == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i != 0) out.append(';');
            if (values.get(i) != null) out.append(values.get(i));
        }
        return out.toString();
    }
}
