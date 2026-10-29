package com.moveinsync.config.core.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * An individual mutation item within a {@link ChangeSet}.
 *
 * Can either set a new value for a given (key, scope, level) or delete the existing override.
 */
public record ChangeItem(
        String key,
        Scope scope,
        Level level,
        Object newValue,
        boolean isDelete
) implements Serializable {

    public ChangeItem {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("ChangeItem key must not be blank");
        }
        Objects.requireNonNull(scope, "ChangeItem scope must not be null");
        Objects.requireNonNull(level, "ChangeItem level must not be null");

        if (!level.matchesScope(scope)) {
            throw new IllegalArgumentException(
                    "Scope " + scope + " does not match level " + level + " constraints"
            );
        }

        if (!isDelete && newValue == null) {
            throw new IllegalArgumentException("newValue must not be null unless isDelete is true");
        }
    }

    public static ChangeItem set(String key, Scope scope, Level level, Object newValue) {
        return new ChangeItem(key, scope, level, newValue, false);
    }

    public static ChangeItem delete(String key, Scope scope, Level level) {
        return new ChangeItem(key, scope, level, null, true);
    }
}
