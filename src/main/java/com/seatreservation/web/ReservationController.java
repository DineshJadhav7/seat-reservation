package com.seatreservation.web;

import com.seatreservation.model.DeclineException;
import com.seatreservation.model.Dtos.ReservationView;
import com.seatreservation.model.Dtos.ReserveRequest;
import com.seatreservation.model.Dtos.ReserveResult;
import com.seatreservation.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

    private final ReservationService service;
    private final AuthHelper auth;

    public ReservationController(ReservationService service, AuthHelper auth) {
        this.service = service;
        this.auth = auth;
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationView> reserve(
            @PathVariable("id") String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestBody ReserveRequest body) {
        String userId = auth.requireUser(authorization); // identity from the token only
        var showId = auth.parseUuid(id, "show");

        String key = (idempotencyHeader != null && !idempotencyHeader.isBlank())
                ? idempotencyHeader.trim() : body.idempotencyKey();
        if (key == null || key.isBlank() || key.length() > 200) {
            throw new DeclineException(400, "idempotency_key_required");
        }

        ReserveResult result = service.reserve(showId, userId, key, body.seats());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replay()) {
            response.header("Idempotent-Replay", "true");
        }
        return response.body(result.reservation());
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationView cancel(
            @PathVariable("id") String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = auth.requireUser(authorization);
        return service.cancel(auth.parseUuid(id, "reservation"), userId);
    }
}
