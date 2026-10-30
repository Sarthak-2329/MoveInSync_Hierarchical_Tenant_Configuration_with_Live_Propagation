package com.moveinsync.config.server.security;

/**
 * Access control roles for the Hierarchical Tenant Configuration Service.
 *
 * <ul>
 *   <li>{@link #ADMIN}: Unrestricted platform access; can create setting definitions,
 *       apply global and tenant-specific change sets, perform rollbacks, and view any tenant.</li>
 *   <li>{@link #OPERATOR}: Write access restricted exclusively to the operator's assigned tenant;
 *       cannot modify global settings, definitions, or other clients.</li>
 *   <li>{@link #VIEWER}: Read-only access to global settings and the client's own tenant overrides;
 *       used by embedded client libraries running in microservices.</li>
 * </ul>
 */
public enum Role {
    ADMIN,
    OPERATOR,
    VIEWER
}
