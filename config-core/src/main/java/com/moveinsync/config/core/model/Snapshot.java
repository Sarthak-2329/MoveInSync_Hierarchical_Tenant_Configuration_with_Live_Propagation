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
 * - Memory / Space: O(D + O_v + R) where D is definitions count, O_v is overrides count, and R is rollouts count.
 */
public final class Snapshot implements Serializable {

    private final long version;
    private final Map<String, SettingDefinition> definitions;
    private final Map<String, Map<Scope, OverrideValue>> overridesByKey;
    private final Map<String, StagedRollout> stagedRollouts;

    /**
     * Constructs a fully immutable Snapshot without staged rollouts.
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
        this(version, definitions, overridesByKey, Collections.emptyMap());
    }

    /**
     * Constructs a fully immutable Snapshot including staged rollouts.
     *
     * @param version monotonic version number
     * @param definitions setting definitions map
     * @param overridesByKey overrides grouped by setting key and scope
     * @param stagedRollouts staged rollouts mapped by rollout ID
     */
    public Snapshot(
            long version,
            Map<String, SettingDefinition> definitions,
            Map<String, Map<Scope, OverrideValue>> overridesByKey,
            Map<String, StagedRollout> stagedRollouts
    ) {
        if (version < 0) {
            throw new IllegalArgumentException("Snapshot version cannot be negative");
        }
        this.version = version;
        Objects.requireNonNull(definitions, "definitions map must not be null");
        Objects.requireNonNull(overridesByKey, "overridesByKey map must not be null");
        Objects.requireNonNull(stagedRollouts, "stagedRollouts map must not be null");

        // Deep defensive copy for definitions
        this.definitions = Collections.unmodifiableMap(new HashMap<>(definitions));

        // Deep defensive copy for nested overrides map
        Map<String, Map<Scope, OverrideValue>> deepCopy = new HashMap<>();
        for (Map.Entry<String, Map<Scope, OverrideValue>> entry : overridesByKey.entrySet()) {
            deepCopy.put(entry.getKey(), Collections.unmodifiableMap(new HashMap<>(entry.getValue())));
        }
        this.overridesByKey = Collections.unmodifiableMap(deepCopy);

        // Deep defensive copy for staged rollouts
        this.stagedRollouts = Collections.unmodifiableMap(new HashMap<>(stagedRollouts));
    }

    /**
     * Creates an initial empty snapshot at version 0.
     */
    public static Snapshot empty() {
        return new Snapshot(0L, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
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

    public Map<String, StagedRollout> stagedRollouts() {
        return stagedRollouts;
    }

    public Map<String, StagedRollout> getStagedRollouts() {
        return stagedRollouts;
    }

    public StagedRollout getStagedRollout(String id) {
        return stagedRollouts.get(id);
    }

    /**
     * Finds an active staged rollout matching the given key, client, and office.
     *
     * @param key setting key
     * @param clientId client ID
     * @param officeId candidate office ID
     * @return the matching active StagedRollout or null if none
     */
    public StagedRollout findActiveRollout(String key, String clientId, String officeId) {
        if (officeId == null) {
            return null;
        }
        for (StagedRollout rollout : stagedRollouts.values()) {
            if (rollout.matches(key, clientId, officeId)) {
                return rollout;
            }
        }
        return null;
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
                && overridesByKey.equals(snapshot.overridesByKey)
                && stagedRollouts.equals(snapshot.stagedRollouts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, definitions, overridesByKey, stagedRollouts);
    }

    @Override
    public String toString() {
        return "Snapshot[version=" + version
                + ", definitions=" + definitions.size()
                + ", overriddenKeys=" + overridesByKey.size()
                + ", stagedRollouts=" + stagedRollouts.size() + "]";
    }
}
