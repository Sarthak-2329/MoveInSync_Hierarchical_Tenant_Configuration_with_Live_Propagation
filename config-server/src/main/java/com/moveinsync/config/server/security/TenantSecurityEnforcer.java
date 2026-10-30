package com.moveinsync.config.server.security;

import com.moveinsync.config.server.dto.ApplyChangeSetRequest;
import com.moveinsync.config.server.dto.ChangeItemDto;
import com.moveinsync.config.server.exception.AccessDeniedException;
import com.moveinsync.config.server.exception.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Enforces Role-Based Access Control (RBAC) and strict Multi-Tenant isolation rules.
 *
 * <p><strong>Rules:</strong>
 * <ul>
 *   <li>{@link Role#ADMIN}: Unrestricted access to all endpoints, global definitions, and any tenant.</li>
 *   <li>{@link Role#OPERATOR}: Write access restricted exclusively to items matching their assigned tenant.
 *       Cannot create setting definitions, perform rollbacks, or modify global settings.</li>
 *   <li>{@link Role#VIEWER}: Read-only access to global settings and their assigned tenant.
 *       Cannot apply change sets or invoke mutating endpoints.</li>
 * </ul>
 */
@Component
public class TenantSecurityEnforcer {

    private final SecurityProperties securityProperties;

    public TenantSecurityEnforcer(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    /**
     * Enforces that the caller has ADMIN privileges.
     */
    public void enforceAdmin() {
        if (!securityProperties.isEnabled()) {
            return;
        }
        AuthenticatedUser user = AuthContext.getCurrentUser();
        if (user == null || !user.isAdmin()) {
            throw new AccessDeniedException("Forbidden: Only administrators can perform this operation");
        }
    }

    /**
     * Enforces write permissions for an atomic change set.
     *
     * @param request change set to be applied
     */
    public void enforceChangeSetWrite(ApplyChangeSetRequest request) {
        if (!securityProperties.isEnabled()) {
            return;
        }
        AuthenticatedUser user = AuthContext.getCurrentUser();
        if (user == null) {
            throw new AccessDeniedException("Unauthorized: Authentication required");
        }

        if (user.isViewer()) {
            throw new AccessDeniedException("Forbidden: Viewers have read-only access");
        }

        if (user.isOperator()) {
            String operatorTenant = user.tenantId();
            for (ChangeItemDto item : request.items()) {
                String itemClient = (item.scope() != null) ? item.scope().clientId() : null;
                if (itemClient == null || !itemClient.equals(operatorTenant)) {
                    throw new AccessDeniedException(
                            "Forbidden: Operators can only modify configuration within their assigned tenant '"
                                    + operatorTenant + "'"
                    );
                }
            }
        }
    }

    /**
     * Enforces write permissions for managing staged rollouts (create, promote, discard).
     *
     * @param targetClientId tenant targeted by the rollout (or null for global)
     */
    public void enforceRolloutWrite(String targetClientId) {
        if (!securityProperties.isEnabled()) {
            return;
        }
        AuthenticatedUser user = AuthContext.getCurrentUser();
        if (user == null) {
            throw new AccessDeniedException("Unauthorized: Authentication required");
        }

        if (user.isViewer()) {
            throw new AccessDeniedException("Forbidden: Viewers have read-only access");
        }

        if (user.isOperator()) {
            String operatorTenant = user.tenantId();
            if (targetClientId == null || !targetClientId.equals(operatorTenant)) {
                throw new AccessDeniedException(
                        "Forbidden: Operators can only manage rollouts for their assigned tenant '"
                                + operatorTenant + "'"
                );
            }
        }
    }

    /**
     * Resolves and verifies the effective tenant for a read query.
     *
     * @param requestedTenant tenant passed via header or query param
     * @return effective authorized tenant ID
     */
    public String resolveEffectiveTenant(String requestedTenant) {
        if (!securityProperties.isEnabled()) {
            return requestedTenant;
        }
        AuthenticatedUser user = AuthContext.getCurrentUser();
        if (user == null) {
            return requestedTenant;
        }

        if (user.isAdmin()) {
            return requestedTenant;
        }

        // For OPERATOR and VIEWER, enforce their assigned tenant
        String assignedTenant = user.tenantId();
        if (requestedTenant != null && !requestedTenant.isBlank() && !requestedTenant.equals(assignedTenant)) {
            throw new AccessDeniedException("Forbidden: Access denied for tenant '" + requestedTenant + "'");
        }
        return assignedTenant;
    }
}
