package com.seatreservation.web;

import com.seatreservation.metrics.SeatMetrics;
import com.seatreservation.model.DeclineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Turns exceptions into JSON. Business declines are 4xx; only genuine bugs reach the 500 handler. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final SeatMetrics metrics;

    public ApiExceptionHandler(SeatMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(DeclineException.class)
    public ResponseEntity<Map<String, Object>> onDecline(DeclineException e) {
        if (e.counted()) {
            metrics.declined(e.reason());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.reason());
        body.putAll(e.extra());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> onBadJson(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_json"));
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<Map<String, Object>> onDbDown(DataAccessResourceFailureException e) {
        log.error("database unavailable", e);
        return ResponseEntity.status(503).body(Map.of("error", "db_unavailable"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onAnythingElse(Exception e) throws Exception {
        if (e instanceof ErrorResponse errorResponse) { // 404 unknown route, 405 wrong method, 415 ...
            return ResponseEntity.status(errorResponse.getStatusCode()).body(Map.of("error", "http_error"));
        }
        log.error("unhandled error", e);
        return ResponseEntity.status(500).body(Map.of("error", "internal_error"));
    }
}
