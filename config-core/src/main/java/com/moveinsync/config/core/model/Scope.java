package com.moveinsync.config.core.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Immutable hierarchical coordinates defining the scope of an override or a query.
 *
 * Coordinates cascade as follows:
 * - GLOBAL: (null, null, null)
 * - CLIENT: (clientId, null, null)
 * - OFFICE: (clientId, officeId, null)
 * - SHIFT:  (clientId, officeId, shiftId)
 */
public record Scope(String clientId, String officeId, String shiftId) implements Serializable {

    public Scope {
        // Normalise empty strings to null for consistent equality checks
        clientId = (clientId != null && clientId.isBlank()) ? null : clientId;
        officeId = (officeId != null && officeId.isBlank()) ? null : officeId;
        shiftId = (shiftId != null && shiftId.isBlank()) ? null : shiftId;

        // Invariant: cannot have officeId without clientId
        if (officeId != null && clientId == null) {
            throw new IllegalArgumentException("officeId cannot be specified without clientId");
        }
        // Invariant: cannot have shiftId without officeId and clientId
        if (shiftId != null && (officeId == null || clientId == null)) {
            throw new IllegalArgumentException("shiftId cannot be specified without officeId and clientId");
        }
    }

    public static Scope global() {
        return new Scope(null, null, null);
    }

    public static Scope client(String clientId) {
        return new Scope(clientId, null, null);
    }

    public static Scope office(String clientId, String officeId) {
        return new Scope(clientId, officeId, null);
    }

    public static Scope shift(String clientId, String officeId, String shiftId) {
        return new Scope(clientId, officeId, shiftId);
    }

    /**
     * Projects this scope down to the specified hierarchy level.
     * For example, if this scope is (client1, office1, shift1) and level is Level.CLIENT,
     * this returns (client1, null, null).
     *
     * @param level hierarchy level to project onto
     * @return scoped coordinates for the specified level, or null if this scope lacks required parent coordinates
     */
    public Scope projectToLevel(Level level) {
        Objects.requireNonNull(level, "level must not be null");
        return switch (level) {
            case GLOBAL -> global();
            case CLIENT -> (clientId != null) ? client(clientId) : null;
            case OFFICE -> (clientId != null && officeId != null) ? office(clientId, officeId) : null;
            case SHIFT -> (clientId != null && officeId != null && shiftId != null) ? shift(clientId, officeId, shiftId) : null;
        };
    }
}
