package com.moveinsync.config.core.engine;

import com.moveinsync.config.core.model.ConfigDelta;
import com.moveinsync.config.core.model.ConfigDelta.DeletedOverrideKey;
import com.moveinsync.config.core.model.OverrideValue;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Calculates the delta between two immutable configuration snapshots.
 *
 * Used for:
 * 1. Bandwidth-efficient live propagation (sending only what changed since client's version).
 * 2. Rollback previews (generating the diff of what will change when rolling back).
 *
 * Computational Complexity:
 * - Time: O(D + O_v) where D is definitions count and O_v is overrides count.
 * - Space: O(Δ) proportional to the number of changed elements.
 */
public final class SnapshotDiffCalculator {

    private SnapshotDiffCalculator() {
        // Utility class
    }

    /**
     * Computes the incremental delta required to transition from `fromSnapshot` to `toSnapshot`.
     *
     * @param fromSnapshot the baseline snapshot (e.g. client's current version)
     * @param toSnapshot the target snapshot (e.g. server's latest version)
     * @return ConfigDelta containing added/updated definitions, added/updated overrides, and deleted overrides
     */
    public static ConfigDelta calculateDiff(Snapshot fromSnapshot, Snapshot toSnapshot) {
        Objects.requireNonNull(fromSnapshot, "fromSnapshot must not be null");
        Objects.requireNonNull(toSnapshot, "toSnapshot must not be null");

        List<SettingDefinition> updatedDefs = new ArrayList<>();
        List<OverrideValue> updatedOverrides = new ArrayList<>();
        List<DeletedOverrideKey> deletedOverrides = new ArrayList<>();

        // 1. Check for new or modified definitions
        for (Map.Entry<String, SettingDefinition> entry : toSnapshot.definitions().entrySet()) {
            SettingDefinition oldDef = fromSnapshot.getDefinition(entry.getKey());
            if (oldDef == null || !oldDef.equals(entry.getValue())) {
                updatedDefs.add(entry.getValue());
            }
        }

        // 2. Check for new or modified overrides
        for (Map.Entry<String, Map<Scope, OverrideValue>> entry : toSnapshot.overridesByKey().entrySet()) {
            String key = entry.getKey();
            for (Map.Entry<Scope, OverrideValue> scopeEntry : entry.getValue().entrySet()) {
                Scope scope = scopeEntry.getKey();
                OverrideValue newOv = scopeEntry.getValue();
                OverrideValue oldOv = fromSnapshot.getOverride(key, scope);
                if (oldOv == null || !Objects.equals(oldOv.value(), newOv.value())) {
                    updatedOverrides.add(newOv);
                }
            }
        }

        // 3. Check for deleted overrides (present in fromSnapshot, missing in toSnapshot)
        for (Map.Entry<String, Map<Scope, OverrideValue>> entry : fromSnapshot.overridesByKey().entrySet()) {
            String key = entry.getKey();
            for (Map.Entry<Scope, OverrideValue> scopeEntry : entry.getValue().entrySet()) {
                Scope scope = scopeEntry.getKey();
                if (toSnapshot.getOverride(key, scope) == null) {
                    deletedOverrides.add(new DeletedOverrideKey(key, scope, scopeEntry.getValue().level()));
                }
            }
        }

        return new ConfigDelta(
                fromSnapshot.version(),
                toSnapshot.version(),
                updatedDefs,
                updatedOverrides,
                deletedOverrides
        );
    }
}
