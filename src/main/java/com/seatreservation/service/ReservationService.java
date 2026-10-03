package com.seatreservation.service;

import com.seatreservation.logging.Logs;
import com.seatreservation.metrics.SeatMetrics;
import com.seatreservation.model.DeclineException;
import com.seatreservation.model.Dtos.CreateShowRequest;
import com.seatreservation.model.Dtos.ReservationView;
import com.seatreservation.model.Dtos.ReserveResult;
import com.seatreservation.model.Dtos.SeatView;
import com.seatreservation.model.Dtos.ShowView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * All the booking rules live here. Everything that decides "who gets the seat" happens inside ONE
 * database transaction, and the decision is taken while holding row locks (SELECT ... FOR UPDATE).
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int MAX_SEATS_PER_REQUEST = 50;
    private static final int MAX_SEATS_PER_SHOW = 100_000;
    private static final int MAX_LABEL_LENGTH = 50;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SeatMetrics metrics;
    private final int defaultPerUserLimit;

    public ReservationService(JdbcTemplate jdbc, TransactionTemplate tx, SeatMetrics metrics,
                              @Value("${app.per-user-limit}") int defaultPerUserLimit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.metrics = metrics;
        this.defaultPerUserLimit = defaultPerUserLimit;
    }

    // ------------------------------------------------------------------ row types / mappers

    /** Internal row; the request_hash is needed for the idempotency comparison. */
    private record ReservationRow(UUID id, UUID showId, String userId, String requestHash,
                                  List<String> seats, long amountPaise, String status) {
        ReservationView toView() {
            return new ReservationView(id.toString(), showId.toString(), userId, seats, amountPaise, status);
        }

        ReservationRow withStatus(String newStatus) {
            return new ReservationRow(id, showId, userId, requestHash, seats, amountPaise, newStatus);
        }
    }

    private record SeatRow(String label, String status) {
    }

    private static final String RESERVATION_COLUMNS =
            "id, show_id, user_id, request_hash, seats, amount_paise, status";

    private static final RowMapper<ReservationRow> RESERVATION_MAPPER = (rs, i) -> new ReservationRow(
            rs.getObject("id", UUID.class),
            rs.getObject("show_id", UUID.class),
            rs.getString("user_id"),
            rs.getString("request_hash"),
            List.of((String[]) rs.getArray("seats").getArray()),
            rs.getLong("amount_paise"),
            rs.getString("status"));

    private static final RowMapper<SeatRow> SEAT_ROW_MAPPER =
            (rs, i) -> new SeatRow(rs.getString("label"), rs.getString("status"));

    // ------------------------------------------------------------------ create show

    public ShowView createShow(CreateShowRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new DeclineException(422, "invalid_name");
        }
        if (req.pricePaise() == null || req.pricePaise() < 0) {
            throw new DeclineException(422, "invalid_price");
        }
        int perUserLimit = req.perUserLimit() == null ? defaultPerUserLimit : req.perUserLimit();
        if (perUserLimit < 1 || perUserLimit > 100) {
            throw new DeclineException(422, "invalid_per_user_limit");
        }
        List<String> labels = req.seats() == null ? List.of()
                : req.seats().stream().map(s -> s == null ? "" : s.trim()).toList();
        if (labels.isEmpty() || labels.size() > MAX_SEATS_PER_SHOW
                || labels.stream().anyMatch(l -> l.isEmpty() || l.length() > MAX_LABEL_LENGTH)
                || labels.stream().distinct().count() != labels.size()) {
            throw new DeclineException(422, "invalid_seats", false,
                    Map.of("detail", "seat labels must be unique, 1-" + MAX_LABEL_LENGTH + " characters, at most " + MAX_SEATS_PER_SHOW + " seats"));
        }

        UUID showId = UUID.randomUUID();
        String[] labelArray = labels.toArray(new String[0]);
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO shows (id, name, price_paise, per_user_limit) VALUES (?,?,?,?)",
                    showId, req.name().trim(), req.pricePaise(), perUserLimit);
            jdbc.update("INSERT INTO seats (show_id, label) SELECT ?, unnest(?::text[])",
                    (PreparedStatementSetter) ps -> {
                        ps.setObject(1, showId);
                        ps.setArray(2, ps.getConnection().createArrayOf("text", labelArray));
                    });
        });

        List<SeatView> seatViews = labels.stream().map(l -> new SeatView(l, "available")).toList();
        return new ShowView(showId.toString(), req.name().trim(), req.pricePaise(), perUserLimit,
                labels.size(), labels.size(), 0, 0, seatViews);
    }

    // ------------------------------------------------------------------ show state

    public ShowView getShow(UUID showId) {
        List<int[]> show = jdbc.query("SELECT price_paise, per_user_limit FROM shows WHERE id = ?",
                (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}, showId);
        if (show.isEmpty()) {
            throw new DeclineException(404, "show_not_found");
        }
        String name = jdbc.queryForObject("SELECT name FROM shows WHERE id = ?", String.class, showId);

        // ONE statement == one consistent snapshot, so the three counts below always add up to the total
        List<SeatView> seats = jdbc.query("SELECT label, status FROM seats WHERE show_id = ? ORDER BY label",
                (rs, i) -> new SeatView(rs.getString("label"), rs.getString("status")), showId);
        int available = 0, held = 0, confirmed = 0;
        for (SeatView s : seats) {
            switch (s.status()) {
                case "available" -> available++;
                case "held" -> held++;
                default -> confirmed++;
            }
        }
        return new ShowView(showId.toString(), name, show.get(0)[0], show.get(0)[1],
                seats.size(), available, held, confirmed, seats);
    }

    // ------------------------------------------------------------------ reserve

    /**
     * Multi-seat behaviour is ALL-OR-NOTHING: if any requested seat isn't available,
     * nothing is booked and the caller gets a 409 listing the taken seats.
     */
    public ReserveResult reserve(UUID showId, String userId, String idempotencyKey, List<String> requestedSeats) {
        List<String> seats = normalise(requestedSeats);
        if (seats.isEmpty() || seats.size() > MAX_SEATS_PER_REQUEST
                || seats.stream().anyMatch(s -> s.length() > MAX_LABEL_LENGTH)) {
            throw new DeclineException(422, "invalid_seats", false,
                    Map.of("detail", "ask for 1 to " + MAX_SEATS_PER_REQUEST + " seats"));
        }
        String requestHash = sha256(showId + "|" + String.join(",", seats));

        ReserveResult result = tx.execute(status -> doReserve(showId, userId, idempotencyKey, seats, requestHash));

        // metrics only AFTER the transaction committed, so we never count work that was rolled back
        if (result.replay()) {
            metrics.declined("idempotent_replay");
        } else {
            metrics.confirmed();
            Logs.info(log, "reservation confirmed", "reservation_id", result.reservation().reservationId(),
                    "user", userId, "seats", seats);
        }
        return result;
    }

    private ReserveResult doReserve(UUID showId, String userId, String key, List<String> seats, String requestHash) {
        // 1) Serialise THIS user's requests (other users are not blocked). That makes the idempotency
        //    check and the per-user limit check race-free for a single user firing parallel requests.
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) t",
                Integer.class, "user:" + userId);

        // 2) Idempotency: same key => same reservation. Same key but a different request => 409.
        List<ReservationRow> prior = jdbc.query(
                "SELECT " + RESERVATION_COLUMNS + " FROM reservations WHERE user_id = ? AND idempotency_key = ?",
                RESERVATION_MAPPER, userId, key);
        if (!prior.isEmpty()) {
            ReservationRow existing = prior.get(0);
            if (!existing.requestHash().equals(requestHash)) {
                throw new DeclineException(409, "idempotency_conflict", true,
                        Map.of("detail", "this idempotency key was already used with a different request"));
            }
            return new ReserveResult(existing.toView(), true);
        }

        List<int[]> show = jdbc.query("SELECT price_paise, per_user_limit FROM shows WHERE id = ?",
                (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}, showId);
        if (show.isEmpty()) {
            throw new DeclineException(404, "show_not_found");
        }
        int pricePaise = show.get(0)[0];
        int perUserLimit = show.get(0)[1];

        // 3) Per-user limit. Safe because we hold this user's advisory lock.
        Long alreadyHeld = jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE show_id = ? AND user_id = ?", Long.class, showId, userId);
        if (alreadyHeld + seats.size() > perUserLimit) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("limit", perUserLimit);
            extra.put("currently_held", alreadyHeld);
            throw new DeclineException(409, "per_user_limit", true, extra);
        }

        // 4) THE atomic decision. Lock the seat rows first (FOR UPDATE), and only then look at their status.
        //    ORDER BY gives every request the same lock order, so two multi-seat requests can't deadlock.
        String[] seatArray = seats.toArray(new String[0]);
        List<SeatRow> locked = jdbc.query(
                "SELECT label, status FROM seats WHERE show_id = ? AND label = ANY(?) ORDER BY label FOR UPDATE",
                (PreparedStatementSetter) ps -> {
                    ps.setObject(1, showId);
                    ps.setArray(2, ps.getConnection().createArrayOf("text", seatArray));
                },
                SEAT_ROW_MAPPER);
        if (locked.size() != seats.size()) {
            List<String> found = locked.stream().map(SeatRow::label).toList();
            List<String> unknown = seats.stream().filter(s -> !found.contains(s)).toList();
            throw new DeclineException(404, "unknown_seat", true, Map.of("seats", unknown));
        }
        List<String> taken = locked.stream().filter(s -> !"available".equals(s.status())).map(SeatRow::label).toList();
        if (!taken.isEmpty()) {
            throw new DeclineException(409, "seat_taken", true, Map.of("seats", taken));
        }

        // 5) We own the rows now: write the reservation and flip the seats.
        UUID reservationId = UUID.randomUUID();
        long amountPaise = (long) pricePaise * seats.size(); // integer paise, never floating point
        jdbc.update("INSERT INTO reservations (id, show_id, user_id, idempotency_key, request_hash, seats, "
                        + "amount_paise, status) VALUES (?,?,?,?,?,?,?,'confirmed')",
                (PreparedStatementSetter) ps -> {
                    ps.setObject(1, reservationId);
                    ps.setObject(2, showId);
                    ps.setString(3, userId);
                    ps.setString(4, key);
                    ps.setString(5, requestHash);
                    ps.setArray(6, ps.getConnection().createArrayOf("text", seatArray));
                    ps.setLong(7, amountPaise);
                });
        jdbc.update("UPDATE seats SET status = 'confirmed', user_id = ?, reservation_id = ? "
                        + "WHERE show_id = ? AND label = ANY(?)",
                (PreparedStatementSetter) ps -> {
                    ps.setString(1, userId);
                    ps.setObject(2, reservationId);
                    ps.setObject(3, showId);
                    ps.setArray(4, ps.getConnection().createArrayOf("text", seatArray));
                });

        ReservationRow created = new ReservationRow(reservationId, showId, userId, requestHash,
                seats, amountPaise, "confirmed");
        return new ReserveResult(created.toView(), false);
    }

    // ------------------------------------------------------------------ cancel

    public ReservationView cancel(UUID reservationId, String userId) {
        ReservationRow result = tx.execute(status -> {
            List<ReservationRow> found = jdbc.query(
                    "SELECT " + RESERVATION_COLUMNS + " FROM reservations WHERE id = ? FOR UPDATE",
                    RESERVATION_MAPPER, reservationId);
            if (found.isEmpty()) {
                throw new DeclineException(404, "reservation_not_found");
            }
            ReservationRow res = found.get(0);
            if (!res.userId().equals(userId)) {
                throw new DeclineException(403, "not_your_reservation");
            }
            if ("cancelled".equals(res.status())) {
                return res; // cancelling twice is harmless
            }
            // same ORDER BY label lock order as reserve(), so cancel and reserve can't deadlock
            jdbc.query("SELECT label FROM seats WHERE reservation_id = ? ORDER BY label FOR UPDATE",
                    (rs, i) -> rs.getString(1), reservationId);
            // guarded on reservation_id: only frees seats that still belong to THIS reservation,
            // so it can never touch a seat that was confirmed to someone else
            jdbc.update("UPDATE seats SET status = 'available', user_id = NULL, reservation_id = NULL "
                    + "WHERE reservation_id = ?", reservationId);
            jdbc.update("UPDATE reservations SET status = 'cancelled', cancelled_at = now() WHERE id = ?",
                    reservationId);
            return res.withStatus("cancelled");
        });
        Logs.info(log, "reservation cancelled", "reservation_id", reservationId, "user", userId);
        return result.toView();
    }

    // ------------------------------------------------------------------ helpers

    static List<String> normalise(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always present in the JDK
        }
    }
}
