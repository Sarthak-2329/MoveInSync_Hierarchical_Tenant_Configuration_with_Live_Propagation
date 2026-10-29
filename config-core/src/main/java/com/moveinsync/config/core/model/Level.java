package com.moveinsync.config.core.model;

/**
 * Represents the hierarchical specificity levels for configuration overrides.
 *
 * Evaluation order is strictly from most specific to least specific:
 * SHIFT -> OFFICE -> CLIENT -> GLOBAL.
 *
 * This enum defines the priority order and provides validation to ensure that
 * a given {@link Scope} matches the expected hierarchy level.
 */
public enum Level {
    GLOBAL(1),
    CLIENT(2),
    OFFICE(3),
    SHIFT(4);

    private final int priority;

    Level(int priority) {
        this.priority = priority;
    }

    /**
     * Higher priority means higher specificity in override resolution.
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Validates whether the given scope coordinates match this hierarchy level.
     *
     * Rules:
     * - GLOBAL: clientId=null, officeId=null, shiftId=null
     * - CLIENT: clientId!=null, officeId=null, shiftId=null
     * - OFFICE: clientId!=null, officeId!=null, shiftId=null
     * - SHIFT:  clientId!=null, officeId!=null, shiftId!=null
     *
     * @param scope the scope coordinates to check
     * @return true if the scope matches this level's structural constraints
     */
    public boolean matchesScope(Scope scope) {
        if (scope == null) {
            return false;
        }
        return switch (this) {
            case GLOBAL -> scope.clientId() == null && scope.officeId() == null && scope.shiftId() == null;
            case CLIENT -> scope.clientId() != null && scope.officeId() == null && scope.shiftId() == null;
            case OFFICE -> scope.clientId() != null && scope.officeId() != null && scope.shiftId() == null;
            case SHIFT -> scope.clientId() != null && scope.officeId() != null && scope.shiftId() != null;
        };
    }
}
