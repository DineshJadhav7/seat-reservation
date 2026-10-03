package com.seatreservation.logging;

import java.util.Map;

/** Builds one JSON log line. Plain JDK on purpose: no library needed, easy to test. */
public final class JsonLines {

    private JsonLines() {
    }

    public static String format(String timestamp, String level, String logger, String message,
                                Map<String, String> fields, String exception) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendPair(sb, "ts", timestamp);
        sb.append(',');
        appendPair(sb, "level", level);
        sb.append(',');
        appendPair(sb, "logger", logger);
        sb.append(',');
        appendPair(sb, "msg", message);
        if (fields != null) {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                sb.append(',');
                appendPair(sb, field.getKey(), field.getValue());
            }
        }
        if (exception != null) {
            sb.append(',');
            appendPair(sb, "exception", exception);
        }
        return sb.append("}\n").toString();
    }

    private static void appendPair(StringBuilder sb, String key, String value) {
        appendString(sb, key);
        sb.append(':');
        appendString(sb, value);
    }

    static void appendString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
