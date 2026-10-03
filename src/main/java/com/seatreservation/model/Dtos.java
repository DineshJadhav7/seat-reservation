package com.seatreservation.model;

import java.util.List;

/** Request/response shapes. JSON field names are snake_case (see application.properties). */
public final class Dtos {

    private Dtos() {
    }

    public record CreateShowRequest(String name, List<String> seats, Integer pricePaise, Integer perUserLimit) {
    }

    public record ReserveRequest(List<String> seats, String idempotencyKey) {
    }

    public record SeatView(String seat, String status) {
    }

    public record ShowView(String id, String name, int pricePaise, int perUserLimit, int totalSeats,
                           int available, int held, int confirmed, List<SeatView> seats) {
    }

    public record ReservationView(String reservationId, String showId, String userId, List<String> seats,
                                  long amountPaise, String status) {
    }

    public record ReserveResult(ReservationView reservation, boolean replay) {
    }
}
