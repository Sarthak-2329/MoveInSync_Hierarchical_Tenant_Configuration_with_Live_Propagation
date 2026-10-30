package com.moveinsync.config.server.security;

import java.util.Objects;

/**
 * Represents the authenticated caller principal established by {@link ApiKeyAuthFilter}.
 *
 * @param key the authenticated API key
 * @param role authorized role
 * @param tenantId assigned tenant identifier ("*" for platform administrators)
 */
public record AuthenticatedUser(String key, Role role, String tenantId) {

    public AuthenticatedUser {
        Objects.requireNonNull(role, "Role must not be null");
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "*";
        }
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean isOperator() {
        return role == Role.OPERATOR;
    }

    public boolean isViewer() {
        return role == Role.VIEWER;
    }

    /**
     * Determines whether this principal is authorized to access data for the target tenant.
     *
     * @param targetTenant the tenant being queried or modified
     * @return true if authorized, false otherwise
     */
    public boolean canAccessTenant(String targetTenant) {
        if (isAdmin()) {
            return true;
        }
        if (targetTenant == null || targetTenant.isBlank() || "*".equals(targetTenant)) {
            return false;
        }
        return this.tenantId.equals(targetTenant);
    }
}
