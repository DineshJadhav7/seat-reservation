package com.seatreservation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prometheus names come out as:
 *   reservations_confirmed_total
 *   reservations_declined_total{reason="seat_taken|per_user_limit|idempotent_replay|..."}
 *   seats_available{show_id}   and   seats{show_id,status}
 */
@Component
public class SeatMetrics {

    private static final List<String> STATUSES = List.of("available", "held", "confirmed");
    private static final List<String> REASONS =
            List.of("seat_taken", "per_user_limit", "idempotent_replay", "idempotency_conflict", "unknown_seat");

    private final MeterRegistry registry;
    private final Counter confirmed;
    private final MultiGauge seatsAvailable;
    private final MultiGauge seatsByStatus;

    public SeatMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder("reservations.confirmed")
                .description("Reservations confirmed (new ones, not replays)")
                .register(registry);
        // create every reason up front so dashboards and alerts never see a missing series
        REASONS.forEach(r -> registry.counter("reservations.declined", "reason", r));
        this.seatsAvailable = MultiGauge.builder("seats.available").register(registry);
        this.seatsByStatus = MultiGauge.builder("seats").register(registry);
    }

    public void confirmed() {
        confirmed.increment();
    }

    public void declined(String reason) {
        registry.counter("reservations.declined", "reason", reason).increment();
    }

    /** showId -> (status -> count). Called at scrape time so the gauges always mirror the DB. */
    public void updateSeatGauges(Map<String, Map<String, Long>> counts) {
        List<MultiGauge.Row<?>> available = new ArrayList<>();
        List<MultiGauge.Row<?>> byStatus = new ArrayList<>();
        counts.forEach((showId, perStatus) -> {
            available.add(MultiGauge.Row.of(Tags.of("show_id", showId), perStatus.getOrDefault("available", 0L)));
            for (String status : STATUSES) {
                byStatus.add(MultiGauge.Row.of(Tags.of("show_id", showId, "status", status),
                        perStatus.getOrDefault(status, 0L)));
            }
        });
        seatsAvailable.register(available, true);
        seatsByStatus.register(byStatus, true);
    }
}
