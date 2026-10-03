package com.seatreservation.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Creates the tables on start-up. Runs in a background thread with retries so the app can boot
 * (and answer /healthz) even if the database is still waking up; /readyz stays 503 until this succeeds.
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final long SCHEMA_LOCK_ID = 727001L;

    private final JdbcTemplate jdbc;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isReady() {
        return ready.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread t = new Thread(this::initWithRetry, "schema-init");
        t.setDaemon(true);
        t.start();
    }

    private void initWithRetry() {
        String ddl = loadSchema();
        long backoffMs = 1000;
        for (int attempt = 1; !ready.get(); attempt++) {
            try {
                jdbc.execute((ConnectionCallback<Void>) con -> {
                    try (Statement st = con.createStatement()) {
                        // advisory lock: two instances booting at once must not race on the DDL
                        st.execute("SELECT pg_advisory_lock(" + SCHEMA_LOCK_ID + ")");
                        try {
                            st.execute(ddl);
                        } finally {
                            st.execute("SELECT pg_advisory_unlock(" + SCHEMA_LOCK_ID + ")");
                        }
                    }
                    return null;
                });
                ready.set(true);
                log.info("schema ready after {} attempt(s)", attempt);
            } catch (Exception e) {
                log.warn("schema init failed (attempt {}): {}", attempt, e.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, 5000);
            }
        }
    }

    private String loadSchema() {
        try (InputStream in = new ClassPathResource("schema.sql").getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read schema.sql", e);
        }
    }
}
