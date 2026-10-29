package com.moveinsync.config.core.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable point-in-time snapshot of the configuration state.
 *
 * Concurrency & Atomicity Guarantee:
 * Once constructed, this object and its internal collections can NEVER be mutated.
 * All mutations construct a brand-new {@code Snapshot} instance. Readers reference
 * this instance via an {@code AtomicReference<Snapshot>} and can execute concurrent,
 * non-blocking in-memory reads without fear of observing partial updates.
 *
 * Computational Complexity:
 * - Read / Lookup: O(1) hash map lookup.
 * - Memory / Space: O(D + O_v) where D is the count of definitions and O_v is the count of overrides.
 */
public final class Snapshot implements Serializable {

    private final long version;
    private final Map<String, SettingDefinition> definitions;
    private final Map<String, Map<Scope, OverrideValue>> overridesByKey;

    /**
     * Constructs a fully immutable Snapshot.
     *
     * @param version monotonic version number
     * @param definitions setting definitions map
     * @param overridesByKey overrides grouped by setting key and scope
     */
    public Snapshot(
            long version,
            Map<String, SettingDefinition> definitions,
            Map<String, Map<Scope, OverrideValue>> overridesByKey
    ) {
        if (version < 0) {
            throw new IllegalArgumentException("Snapshot version cannot be negative");
        }
        this.version = version;
        Objects.requireNonNull(definitions, "definitions map must not be null");
        Objects.requireNonNull(overridesByKey, "overridesByKey map must not be null");

        // Deep defensive copy for definitions
        this.definitions = Collections.unmodifiableMap(new HashMap<>(definitions));

        // Deep defensive copy for nested overrides map
        Map<String, Map<Scope, OverrideValue>> deepCopy = new HashMap<>();
        for (Map.Entry<String, Map<Scope, OverrideValue>> entry : overridesByKey.entrySet()) {
            deepCopy.put(entry.getKey(), Collections.unmodifiableMap(new HashMap<>(entry.getValue())));
        }
        this.overridesByKey = Collections.unmodifiableMap(deepCopy);
    }

    /**
     * Creates an initial empty snapshot at version 0.
     */
    public static Snapshot empty() {
        return new Snapshot(0L, Collections.emptyMap(), Collections.emptyMap());
    }

    public long version() {
        return version;
    }

    public long getVersion() {
        return version;
    }

    public Map<String, SettingDefinition> definitions() {
        return definitions;
    }

    public Map<String, SettingDefinition> getDefinitions() {
        return definitions;
    }

    public SettingDefinition getDefinition(String key) {
        return definitions.get(key);
    }

    public boolean hasDefinition(String key) {
        return definitions.containsKey(key);
    }

    public Map<String, Map<Scope, OverrideValue>> overridesByKey() {
        return overridesByKey;
    }

    public Map<String, Map<Scope, OverrideValue>> getOverridesByKey() {
        return overridesByKey;
    }

    /**
     * Retrieves an override for a key at an exact scope coordinates.
     *
     * @return OverrideValue if present, or null if no override is defined at this exact scope
     */
    public OverrideValue getOverride(String key, Scope scope) {
        Map<Scope, OverrideValue> scopeMap = overridesByKey.get(key);
        if (scopeMap == null) {
            return null;
        }
        return scopeMap.get(scope);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Snapshot snapshot)) return false;
        return version == snapshot.version
                && definitions.equals(snapshot.definitions)
                && overridesByKey.equals(snapshot.overridesByKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, definitions, overridesByKey);
    }

    @Override
    public String toString() {
        return "Snapshot[version=" + version
                + ", definitions=" + definitions.size()
                + ", overriddenKeys=" + overridesByKey.size() + "]";
    }
}
