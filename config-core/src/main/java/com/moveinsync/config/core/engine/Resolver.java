package com.moveinsync.config.core.engine;

import com.moveinsync.config.core.exception.SettingNotFoundException;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.OverrideValue;
import com.moveinsync.config.core.model.ResolutionExplanation;
import com.moveinsync.config.core.model.ResolutionExplanation.CandidateEvaluation;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure functional hierarchical configuration resolver.
 *
 * Implements the most-specific-wins algorithm across the hierarchy:
 * 1. SHIFT  (clientId, officeId, shiftId)
 * 2. OFFICE (clientId, officeId, null)
 * 3. CLIENT (clientId, null, null)
 * 4. GLOBAL override (null, null, null)
 * 5. Definition Default (fallback)
 *
 * Computational Complexity:
 * - Time: O(1) constant time. At most 4 hash map lookups and 1 definition lookup.
 * - Space: O(1) auxiliary space (zero allocations on the read hot-path).
 */
public final class Resolver {

    private Resolver() {
        // Utility class
    }

    /**
     * Resolves the effective setting value for a given key and query scope.
     *
     * @param snapshot the immutable configuration snapshot
     * @param key the setting key
     * @param scope the query scope (may specify client, office, shift, or be global)
     * @return the winning value
     * @throws SettingNotFoundException if the setting definition does not exist in the snapshot
     */
    public static Object resolve(Snapshot snapshot, String key, Scope scope) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(key, "setting key must not be null");
        Scope effectiveScope = (scope != null) ? scope : Scope.global();

        SettingDefinition definition = snapshot.getDefinition(key);
        if (definition == null) {
            throw new SettingNotFoundException(key);
        }

        // 1. Check SHIFT level override
        Scope shiftScope = effectiveScope.projectToLevel(Level.SHIFT);
        if (shiftScope != null) {
            OverrideValue shiftOverride = snapshot.getOverride(key, shiftScope);
            if (shiftOverride != null) {
                return shiftOverride.value();
            }
        }

        // 2. Check OFFICE level override
        Scope officeScope = effectiveScope.projectToLevel(Level.OFFICE);
        if (officeScope != null) {
            OverrideValue officeOverride = snapshot.getOverride(key, officeScope);
            if (officeOverride != null) {
                return officeOverride.value();
            }
        }

        // 3. Check CLIENT level override
        Scope clientScope = effectiveScope.projectToLevel(Level.CLIENT);
        if (clientScope != null) {
            OverrideValue clientOverride = snapshot.getOverride(key, clientScope);
            if (clientOverride != null) {
                return clientOverride.value();
            }
        }

        // 4. Check GLOBAL level override
        OverrideValue globalOverride = snapshot.getOverride(key, Scope.global());
        if (globalOverride != null) {
            return globalOverride.value();
        }

        // 5. Fallback to Setting Definition Default
        return definition.defaultValue();
    }

    /**
     * Resolves the setting value safely cast to the requested type.
     */
    @SuppressWarnings("unchecked")
    public static <T> T resolve(Snapshot snapshot, String key, Scope scope, Class<T> expectedType) {
        Object raw = resolve(snapshot, key, scope);
        if (expectedType.isInstance(raw)) {
            return expectedType.cast(raw);
        }
        throw new ClassCastException("Resolved value for '" + key + "' is " + raw.getClass().getName()
                + ", not assignable to " + expectedType.getName());
    }

    /**
     * Produces an audit trace explaining how the value was resolved across all levels.
     *
     * @param snapshot the immutable snapshot
     * @param key the setting key
     * @param scope the query scope
     * @return detailed ResolutionExplanation with all evaluated levels
     */
    public static ResolutionExplanation explain(Snapshot snapshot, String key, Scope scope) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(key, "setting key must not be null");
        Scope effectiveScope = (scope != null) ? scope : Scope.global();

        SettingDefinition definition = snapshot.getDefinition(key);
        if (definition == null) {
            throw new SettingNotFoundException(key);
        }

        List<CandidateEvaluation> evaluations = new ArrayList<>(5);
        Level winningLevel = null;
        Object winningValue = null;

        // 1. Evaluate SHIFT
        Scope shiftScope = effectiveScope.projectToLevel(Level.SHIFT);
        if (shiftScope != null) {
            OverrideValue override = snapshot.getOverride(key, shiftScope);
            boolean exists = override != null;
            boolean isWinner = exists && winningLevel == null;
            if (isWinner) {
                winningLevel = Level.SHIFT;
                winningValue = override.value();
            }
            evaluations.add(new CandidateEvaluation(
                    Level.SHIFT, shiftScope, exists ? override.value() : null, exists, isWinner,
                    exists ? (isWinner ? "Selected: Most specific match at SHIFT level" : "Shadowed") : "No override configured at SHIFT level"
            ));
        }

        // 2. Evaluate OFFICE
        Scope officeScope = effectiveScope.projectToLevel(Level.OFFICE);
        if (officeScope != null) {
            OverrideValue override = snapshot.getOverride(key, officeScope);
            boolean exists = override != null;
            boolean isWinner = exists && winningLevel == null;
            if (isWinner) {
                winningLevel = Level.OFFICE;
                winningValue = override.value();
            }
            evaluations.add(new CandidateEvaluation(
                    Level.OFFICE, officeScope, exists ? override.value() : null, exists, isWinner,
                    exists ? (isWinner ? "Selected: Match at OFFICE level" : "Shadowed by higher priority SHIFT override") : "No override configured at OFFICE level"
            ));
        }

        // 3. Evaluate CLIENT
        Scope clientScope = effectiveScope.projectToLevel(Level.CLIENT);
        if (clientScope != null) {
            OverrideValue override = snapshot.getOverride(key, clientScope);
            boolean exists = override != null;
            boolean isWinner = exists && winningLevel == null;
            if (isWinner) {
                winningLevel = Level.CLIENT;
                winningValue = override.value();
            }
            evaluations.add(new CandidateEvaluation(
                    Level.CLIENT, clientScope, exists ? override.value() : null, exists, isWinner,
                    exists ? (isWinner ? "Selected: Match at CLIENT level" : "Shadowed by higher priority override") : "No override configured at CLIENT level"
            ));
        }

        // 4. Evaluate GLOBAL override
        OverrideValue globalOverride = snapshot.getOverride(key, Scope.global());
        boolean hasGlobalOverride = globalOverride != null;
        boolean isGlobalWinner = hasGlobalOverride && winningLevel == null;
        if (isGlobalWinner) {
            winningLevel = Level.GLOBAL;
            winningValue = globalOverride.value();
        }
        evaluations.add(new CandidateEvaluation(
                Level.GLOBAL, Scope.global(), hasGlobalOverride ? globalOverride.value() : null,
                hasGlobalOverride, isGlobalWinner,
                hasGlobalOverride ? (isGlobalWinner ? "Selected: Match at GLOBAL override level" : "Shadowed by higher priority override") : "No override configured at GLOBAL level"
        ));

        // 5. Evaluate Schema Default
        boolean isDefaultWinner = winningLevel == null;
        if (isDefaultWinner) {
            winningLevel = Level.GLOBAL;
            winningValue = definition.defaultValue();
        }
        evaluations.add(new CandidateEvaluation(
                Level.GLOBAL, Scope.global(), definition.defaultValue(), true, isDefaultWinner,
                isDefaultWinner ? "Selected: Fallback to Setting Definition Default" : "Shadowed by explicit override"
        ));

        return new ResolutionExplanation(key, effectiveScope, winningLevel, winningValue, evaluations);
    }
}
