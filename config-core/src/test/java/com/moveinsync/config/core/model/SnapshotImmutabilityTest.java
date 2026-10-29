package com.moveinsync.config.core.model;

import com.moveinsync.config.core.engine.ChangeSetApplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotImmutabilityTest {

    @Test
    @DisplayName("Definitions map returned by Snapshot cannot be modified")
    void definitionsMapIsUnmodifiable() {
        Snapshot empty = Snapshot.empty();
        SettingDefinition def = new SettingDefinition(
                "key1",
                SettingType.NUMBER,
                "desc",
                ValidationRule.empty(),
                10.0
        );
        Snapshot snapshot = ChangeSetApplier.registerDefinition(empty, def, 1L);

        Map<String, SettingDefinition> defs = snapshot.definitions();
        assertThatThrownBy(() -> defs.put("newKey", def))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Overrides map and nested scope maps cannot be modified")
    void overridesMapIsUnmodifiable() {
        SettingDefinition def = new SettingDefinition("key1", SettingType.NUMBER, "desc", ValidationRule.empty(), 10.0);
        Snapshot snapshot = ChangeSetApplier.registerDefinition(Snapshot.empty(), def, 1L);

        // Try modifying top-level overridesByKey map
        Map<String, Map<Scope, OverrideValue>> overrides = snapshot.overridesByKey();
        assertThatThrownBy(() -> overrides.put("key2", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Defensive copying protects Snapshot from external mutations to input maps")
    void defensiveCopyingInConstructor() {
        Map<String, SettingDefinition> mutableDefs = new HashMap<>();
        SettingDefinition def1 = new SettingDefinition("key1", SettingType.NUMBER, "desc", ValidationRule.empty(), 10.0);
        mutableDefs.put("key1", def1);

        Map<String, Map<Scope, OverrideValue>> mutableOverrides = new HashMap<>();
        Map<Scope, OverrideValue> scopeMap = new HashMap<>();
        scopeMap.put(Scope.global(), new OverrideValue("key1", Scope.global(), Level.GLOBAL, 20.0));
        mutableOverrides.put("key1", scopeMap);

        Snapshot snapshot = new Snapshot(1L, mutableDefs, mutableOverrides);

        // Mutate original maps
        SettingDefinition def2 = new SettingDefinition("key2", SettingType.NUMBER, "desc", ValidationRule.empty(), 30.0);
        mutableDefs.put("key2", def2);
        scopeMap.put(Scope.client("c1"), new OverrideValue("key1", Scope.client("c1"), Level.CLIENT, 50.0));

        // Snapshot internal state remains pristine
        assertThat(snapshot.hasDefinition("key2")).isFalse();
        assertThat(snapshot.getOverride("key1", Scope.client("c1"))).isNull();
    }
}
