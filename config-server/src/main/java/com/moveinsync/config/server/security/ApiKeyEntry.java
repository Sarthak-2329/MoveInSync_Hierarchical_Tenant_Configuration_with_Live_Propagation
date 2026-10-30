package com.moveinsync.config.server.security;

import java.util.Objects;

/**
 * Maps a secret API key to an authorized role and tenant boundary.
 *
 * @param key secret API key value transmitted via the {@code X-API-KEY} header
 * @param role authorized role (ADMIN, OPERATOR, VIEWER)
 * @param tenantId assigned tenant identifier, or {@code "*"} for platform-wide admin access
 */
public record ApiKeyEntry(String key, Role role, String tenantId) {

    public ApiKeyEntry {
        Objects.requireNonNull(key, "API key must not be null");
        Objects.requireNonNull(role, "Role must not be null");
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "*";
        }
    }
}
