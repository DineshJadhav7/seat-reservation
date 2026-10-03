package com.seatreservation.web;

import com.seatreservation.logging.Logs;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request a correlation id (X-Request-Id header, or a generated one), puts it in the
 * logging MDC so every log line for that request carries it, echoes it back, and logs one access line.
 */
@Component("seatReservationRequestContextFilter")
public class RequestContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            String path = request.getRequestURI();
            if (!path.equals("/metrics") && !path.equals("/healthz")) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                Logs.info(log, "request", "method", request.getMethod(), "path", path,
                        "status", response.getStatus(), "ms", ms);
            }
            MDC.remove("requestId");
        }
    }
}
