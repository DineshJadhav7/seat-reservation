package com.seatreservation.web;

import com.seatreservation.metrics.SeatGaugeRefresher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Before Actuator renders GET /metrics, refresh the seat gauges from the database. */
@Component
public class MetricsRefreshFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MetricsRefreshFilter.class);

    private final SeatGaugeRefresher refresher;

    public MetricsRefreshFilter(SeatGaugeRefresher refresher) {
        this.refresher = refresher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("/metrics".equals(request.getRequestURI())) {
            try {
                refresher.refresh();
            } catch (Exception e) {
                // serve the previous gauge values rather than failing the whole scrape
                log.warn("could not refresh seat gauges: {}", e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
