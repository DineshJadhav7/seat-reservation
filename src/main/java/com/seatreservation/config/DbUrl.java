package com.seatreservation.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Render (and most hosts) give us postgres://user:pass@host:port/db, but JDBC wants
 * jdbc:postgresql://host:port/db with the credentials passed separately. This converts one into the other.
 */
public record DbUrl(String jdbcUrl, String user, String password) {

    public static DbUrl parse(String raw) {
        if (raw.startsWith("jdbc:")) {
            return new DbUrl(raw, null, null); // already JDBC; credentials (if any) are inside the URL
        }
        // swap the scheme so java.net.URI will split host / port / path / user-info for us
        URI uri = URI.create(raw.replaceFirst("^postgres(ql)?://", "http://"));
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String db = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        String jdbc = "jdbc:postgresql://" + uri.getHost() + ":" + port + "/" + db;
        if (uri.getRawQuery() != null) {
            jdbc += "?" + uri.getRawQuery();
        }
        String user = null;
        String password = null;
        if (uri.getRawUserInfo() != null) {
            String[] parts = uri.getRawUserInfo().split(":", 2);
            user = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (parts.length > 1) {
                password = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return new DbUrl(jdbc, user, password);
    }
}
