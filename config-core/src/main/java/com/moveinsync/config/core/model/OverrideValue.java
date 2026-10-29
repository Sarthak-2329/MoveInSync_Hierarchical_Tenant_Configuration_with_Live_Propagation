package com.moveinsync.config.core.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * An explicit configuration value bound to a specific hierarchy scope and level.
 */
public record OverrideValue(
        String key,
        Scope scope,
        Level level,
        Object value
) implements Serializable {

    public OverrideValue {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Override key must not be blank");
        }
        Objects.requireNonNull(scope, "Override scope must not be null for key: " + key);
        Objects.requireNonNull(level, "Override level must not be null for key: " + key);
        Objects.requireNonNull(value, "Override value must not be null for key: " + key);

        if (!level.matchesScope(scope)) {
            throw new IllegalArgumentException(
                    "Scope " + scope + " does not match level " + level + " constraints"
            );
        }
    }
}
