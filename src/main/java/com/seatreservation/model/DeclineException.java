package com.seatreservation.model;

import java.util.Map;

/**
 * A clean business outcome (seat taken, over limit, bad auth ...). Becomes a 4xx response.
 * Anything else that escapes is a bug and becomes a 5xx.
 */
public class DeclineException extends RuntimeException {

    private final int status;
    private final String reason;
    private final boolean counted;
    private final Map<String, Object> extra;

    public DeclineException(int status, String reason) {
        this(status, reason, false, Map.of());
    }

    public DeclineException(int status, String reason, boolean counted, Map<String, Object> extra) {
        super(reason, null, false, false); // no stack trace: these are expected and can be very frequent
        this.status = status;
        this.reason = reason;
        this.counted = counted;
        this.extra = extra;
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    /** true -> also increments reservations_declined_total{reason=...} */
    public boolean counted() {
        return counted;
    }

    public Map<String, Object> extra() {
        return extra;
    }
}
