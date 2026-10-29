package com.moveinsync.config.core.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Represents the incremental delta between two snapshot versions.
 *
 * Used for bandwidth-efficient live propagation so clients do not have
 * to download the entire configuration snapshot after every mutation.
 */
public record ConfigDelta(
        long fromVersion,
        long toVersion,
        List<SettingDefinition> updatedDefinitions,
        List<OverrideValue> updatedOverrides,
        List<DeletedOverrideKey> deletedOverrides
) implements Serializable {

    public ConfigDelta {
        updatedDefinitions = (updatedDefinitions != null)
                ? Collections.unmodifiableList(List.copyOf(updatedDefinitions))
                : Collections.emptyList();
        updatedOverrides = (updatedOverrides != null)
                ? Collections.unmodifiableList(List.copyOf(updatedOverrides))
                : Collections.emptyList();
        deletedOverrides = (deletedOverrides != null)
                ? Collections.unmodifiableList(List.copyOf(deletedOverrides))
                : Collections.emptyList();
    }

    public static ConfigDelta empty(long version) {
        return new ConfigDelta(version, version, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    public boolean isEmpty() {
        return updatedDefinitions.isEmpty() && updatedOverrides.isEmpty() && deletedOverrides.isEmpty();
    }

    /**
     * Identifies an override that was removed.
     */
    public record DeletedOverrideKey(
            String key,
            Scope scope,
            Level level
    ) implements Serializable {}
}
