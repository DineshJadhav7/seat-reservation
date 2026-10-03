package com.seatreservation.web;

import com.seatreservation.model.DeclineException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Demo auth: the bearer token IS the user id (swap for JWT verification in production).
 * What matters here is that identity only ever comes from the header, never from the request body.
 */
@Component
public class AuthHelper {

    private final String adminToken;

    public AuthHelper(@Value("${app.admin-token}") String adminToken) {
        this.adminToken = adminToken;
    }

    public String requireUser(String authorizationHeader) {
        return bearerToken(authorizationHeader);
    }

    public void requireAdmin(String authorizationHeader) {
        if (!adminToken.equals(bearerToken(authorizationHeader))) {
            throw new DeclineException(403, "admin_only");
        }
    }

    public UUID parseUuid(String value, String what) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DeclineException(404, what + "_not_found");
        }
    }

    private String bearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new DeclineException(401, "missing_token");
        }
        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            throw new DeclineException(401, "missing_token");
        }
        return token;
    }
}
