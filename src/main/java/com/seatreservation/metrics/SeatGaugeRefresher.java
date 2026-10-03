package com.seatreservation.metrics;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Recomputes the seat gauges from the database. Called right before each /metrics scrape so the numbers can
 * never drift from the real state. Uses the small separate pool, so a scrape doesn't queue behind buyers.
 */
@Component
public class SeatGaugeRefresher {

    private final JdbcTemplate jdbc;
    private final SeatMetrics metrics;

    public SeatGaugeRefresher(@Qualifier("healthDataSource") DataSource healthDataSource, SeatMetrics metrics) {
        this.jdbc = new JdbcTemplate(healthDataSource);
        this.jdbc.setQueryTimeout(5);
        this.metrics = metrics;
    }

    public void refresh() {
        Map<String, Map<String, Long>> counts = new HashMap<>();
        jdbc.query("SELECT show_id::text AS show_id, status, count(*) AS n FROM seats GROUP BY show_id, status",
                rs -> {
                    counts.computeIfAbsent(rs.getString("show_id"), k -> new HashMap<>())
                            .put(rs.getString("status"), rs.getLong("n"));
                });
        metrics.updateSeatGauges(counts);
    }
}
