package com.moveinsync.config.core.engine;

import com.moveinsync.config.core.exception.SettingNotFoundException;
import com.moveinsync.config.core.exception.ValidationException;
import com.moveinsync.config.core.exception.VersionConflictException;
import com.moveinsync.config.core.model.ChangeItem;
import com.moveinsync.config.core.model.ChangeSet;
import com.moveinsync.config.core.model.ConfigDelta;
import com.moveinsync.config.core.model.ConfigDelta.DeletedOverrideKey;
import com.moveinsync.config.core.model.OverrideValue;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.Snapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure functional engine for applying change sets and definitions to produce new immutable snapshots.
 *
 * Guarantees:
 * 1. Atomicity: All items in a ChangeSet are pre-validated before any state is modified.
 *    If any single item is invalid, a {@link ValidationException} is thrown and the existing
 *    snapshot remains 100% untouched.
 * 2. Monotonicity: The new version MUST be strictly greater than the current snapshot version.
 * 3. Immutability: The previous snapshot is never modified; a brand-new {@link Snapshot} instance is returned.
 *
 * Computational Complexity:
 * - Time: O(D + O_v + K) where D is definitions count, O_v is overrides count, and K is change items count.
 * - Space: O(D + O_v) to construct the new immutable snapshot.
 */
public final class ChangeSetApplier {

    private ChangeSetApplier() {
        // Utility class
    }

    /**
     * Applies an atomic ChangeSet to the current snapshot, producing a new immutable snapshot.
     *
     * @param current current immutable snapshot
     * @param changeSet batch of changes to apply
     * @param newVersion strictly increasing new version number
     * @return a brand-new immutable Snapshot incorporating all changes
     * @throws VersionConflictException if newVersion <= current.version()
     * @throws ValidationException if any item violates schema, bounds, or level constraints
     * @throws SettingNotFoundException if an item targets an unknown setting key
     */
    public static Snapshot applyChangeSet(Snapshot current, ChangeSet changeSet, long newVersion) {
        Objects.requireNonNull(current, "current snapshot must not be null");
        Objects.requireNonNull(changeSet, "changeSet must not be null");

        // 1. Enforce monotonic version progression
        if (newVersion <= current.version()) {
            throw new VersionConflictException(current.version(), newVersion);
        }

        // 2. Pre-validation phase: Validate EVERY item before mutating anything.
        // If any item fails validation, an exception is thrown immediately and no state is modified.
        record ValidatedMutation(ChangeItem originalItem, Object coercedValue) {}
        List<ValidatedMutation> validatedMutations = new ArrayList<>(changeSet.items().size());

        for (ChangeItem item : changeSet.items()) {
            SettingDefinition def = current.getDefinition(item.key());
            if (def == null) {
                throw new SettingNotFoundException(item.key());
            }

            if (!item.level().matchesScope(item.scope())) {
                throw new ValidationException(String.format(
                        "Change item scope %s does not match level %s constraints for key '%s'",
                        item.scope(), item.level(), item.key()));
            }

            Object coercedValue = null;
            if (!item.isDelete()) {
                coercedValue = ValueValidator.validateAndCoerce(def, item.newValue());
            }
            validatedMutations.add(new ValidatedMutation(item, coercedValue));
        }

        // 3. Build new state by cloning existing maps and applying the validated mutations
        Map<String, Map<Scope, OverrideValue>> newOverrides = new HashMap<>();
        for (Map.Entry<String, Map<Scope, OverrideValue>> entry : current.overridesByKey().entrySet()) {
            newOverrides.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }

        for (ValidatedMutation mutation : validatedMutations) {
            ChangeItem item = mutation.originalItem();
            Map<Scope, OverrideValue> keyOverrides = newOverrides.computeIfAbsent(item.key(), k -> new HashMap<>());

            if (item.isDelete()) {
                keyOverrides.remove(item.scope());
                if (keyOverrides.isEmpty()) {
                    newOverrides.remove(item.key());
                }
            } else {
                keyOverrides.put(item.scope(), new OverrideValue(item.key(), item.scope(), item.level(), mutation.coercedValue()));
            }
        }

        return new Snapshot(newVersion, current.definitions(), newOverrides);
    }

    /**
     * Registers or updates a setting definition, producing a new immutable snapshot.
     *
     * @param current current snapshot
     * @param definition setting definition to register
     * @param newVersion strictly increasing version
     * @return new Snapshot with the registered definition
     */
    public static Snapshot registerDefinition(Snapshot current, SettingDefinition definition, long newVersion) {
        Objects.requireNonNull(current, "current snapshot must not be null");
        Objects.requireNonNull(definition, "definition must not be null");

        if (newVersion <= current.version()) {
            throw new VersionConflictException(current.version(), newVersion);
        }

        // Validate the default value against the definition's own rules
        Object coercedDefault = ValueValidator.validateAndCoerce(definition, definition.defaultValue());
        SettingDefinition validatedDef = new SettingDefinition(
                definition.key(),
                definition.type(),
                definition.description(),
                definition.validationRule(),
                coercedDefault
        );

        Map<String, SettingDefinition> newDefs = new HashMap<>(current.definitions());
        newDefs.put(validatedDef.key(), validatedDef);

        return new Snapshot(newVersion, newDefs, current.overridesByKey());
    }

    /**
     * Applies an incremental delta to an existing snapshot to advance it to the delta's target version.
     * Used by embedded clients to update their in-memory snapshot with minimal CPU and network overhead.
     *
     * @param current current snapshot
     * @param delta incremental changes from server
     * @return new Snapshot at delta.toVersion()
     */
    public static Snapshot applyDelta(Snapshot current, ConfigDelta delta) {
        Objects.requireNonNull(current, "current snapshot must not be null");
        Objects.requireNonNull(delta, "delta must not be null");

        if (delta.toVersion() < current.version()) {
            throw new VersionConflictException(current.version(), delta.toVersion());
        }
        if (delta.toVersion() == current.version() && delta.isEmpty()) {
            return current;
        }

        // Clone definitions
        Map<String, SettingDefinition> newDefs = new HashMap<>(current.definitions());
        for (SettingDefinition def : delta.updatedDefinitions()) {
            newDefs.put(def.key(), def);
        }

        // Clone overrides
        Map<String, Map<Scope, OverrideValue>> newOverrides = new HashMap<>();
        for (Map.Entry<String, Map<Scope, OverrideValue>> entry : current.overridesByKey().entrySet()) {
            newOverrides.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }

        // Apply deleted overrides
        for (DeletedOverrideKey deleted : delta.deletedOverrides()) {
            Map<Scope, OverrideValue> scopeMap = newOverrides.get(deleted.key());
            if (scopeMap != null) {
                scopeMap.remove(deleted.scope());
                if (scopeMap.isEmpty()) {
                    newOverrides.remove(deleted.key());
                }
            }
        }

        // Apply updated/added overrides
        for (OverrideValue ov : delta.updatedOverrides()) {
            newOverrides.computeIfAbsent(ov.key(), k -> new HashMap<>()).put(ov.scope(), ov);
        }

        return new Snapshot(delta.toVersion(), newDefs, newOverrides);
    }
}
