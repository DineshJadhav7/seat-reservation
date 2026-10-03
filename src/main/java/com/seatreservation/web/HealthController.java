package com.seatreservation.web;

import com.seatreservation.config.SchemaInitializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.Map;

@RestController
public class HealthController {

    private final JdbcTemplate healthJdbc;
    private final SchemaInitializer schema;

    public HealthController(@Qualifier("healthDataSource") DataSource healthDataSource, SchemaInitializer schema) {
        this.healthJdbc = new JdbcTemplate(healthDataSource);
        this.healthJdbc.setQueryTimeout(2);
        this.schema = schema;
    }

    /** Liveness: the process is up. Deliberately does not touch the DB. */
    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "ok");
    }

    /** Readiness: the DB must actually answer. Fails closed (503) on any problem. */
    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> readyz() {
        try {
            if (!schema.isReady()) {
                return ResponseEntity.status(503).body(Map.of("status", "not_ready", "reason", "schema_not_ready"));
            }
            healthJdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "ready"));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("status", "not_ready", "reason", "db_unreachable"));
        }
    }
}
