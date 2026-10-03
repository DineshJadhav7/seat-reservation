package com.seatreservation.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Builds the connection pools from a single DATABASE_URL (see DbUrl for the format conversion).
 */
@Configuration
public class DatabaseConfig {

    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${DATABASE_URL:postgresql://postgres:postgres@localhost:5432/seats}") String url,
            @Value("${app.db.pool-max}") int poolMax) {
        HikariConfig cfg = baseConfig(url, "seats-pool");
        cfg.setMaximumPoolSize(poolMax);
        cfg.setMinimumIdle(2);
        // Under a stampede requests queue for a connection. Wait long rather than failing with a 5xx.
        cfg.setConnectionTimeout(120_000);
        // < 0 means: don't fail application start-up if the DB isn't reachable yet (cold start).
        // /readyz reports 503 until the DB answers.
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }

    /**
     * A tiny separate pool for /readyz and the /metrics gauge refresh. If the main pool is saturated by a
     * burst, the probes must still answer quickly instead of queueing behind buyers.
     */
    @Bean
    public DataSource healthDataSource(
            @Value("${DATABASE_URL:postgresql://postgres:postgres@localhost:5432/seats}") String url) {
        HikariConfig cfg = baseConfig(url, "health-pool");
        cfg.setMaximumPoolSize(3);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(2_000);
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }

    private HikariConfig baseConfig(String rawUrl, String poolName) {
        DbUrl db = DbUrl.parse(rawUrl);
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName(poolName);
        cfg.setJdbcUrl(db.jdbcUrl());
        if (db.user() != null) {
            cfg.setUsername(db.user());
        }
        if (db.password() != null) {
            cfg.setPassword(db.password());
        }
        return cfg;
    }
}
