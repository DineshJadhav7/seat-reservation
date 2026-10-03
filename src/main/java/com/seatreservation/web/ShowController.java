package com.seatreservation.web;

import com.seatreservation.model.Dtos.CreateShowRequest;
import com.seatreservation.model.Dtos.ShowView;
import com.seatreservation.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShowController {

    private final ReservationService service;
    private final AuthHelper auth;

    public ShowController(ReservationService service, AuthHelper auth) {
        this.service = service;
        this.auth = auth;
    }

    @PostMapping("/shows")
    public ResponseEntity<ShowView> createShow(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody CreateShowRequest body) {
        auth.requireAdmin(authorization);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createShow(body));
    }

    @GetMapping("/shows/{id}")
    public ShowView getShow(@PathVariable("id") String id) {
        return service.getShow(auth.parseUuid(id, "show"));
    }
}
