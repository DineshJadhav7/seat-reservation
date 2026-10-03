package com.seatreservation.logging;

import org.slf4j.Logger;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

/** Small helper so a log line can carry extra JSON fields: Logs.info(log, "msg", "user", id, "seats", seats). */
public final class Logs {

    private Logs() {
    }

    public static void info(Logger log, String message, Object... keyValues) {
        List<String> keys = new ArrayList<>();
        try {
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                String key = String.valueOf(keyValues[i]);
                MDC.put(key, String.valueOf(keyValues[i + 1]));
                keys.add(key);
            }
            log.info(message);
        } finally {
            for (String key : keys) {
                MDC.remove(key);
            }
        }
    }
}
