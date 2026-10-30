package com.moveinsync.config.client;

import com.moveinsync.config.client.store.SnapshotStore;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.OverrideValue;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.core.model.TimeWindow;
import com.moveinsync.config.core.model.ValidationRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigViewTest {

    @Test
    @DisplayName("Point-in-time Multi-Key Consistency: ConfigView stays anchored to its captured snapshot")
    void multiKeyConsistency() {
        SettingDefinition startDef = new SettingDefinition("window.start", SettingType.NUMBER, "Start", ValidationRule.empty(), 20.0);
        SettingDefinition endDef = new SettingDefinition("window.end", SettingType.NUMBER, "End", ValidationRule.empty(), 6.0);

        // State 1: start=20.0, end=6.0
        Snapshot snapshotV1 = new Snapshot(1L, Map.of("window.start", startDef, "window.end", endDef), Map.of());
        SnapshotStore store = new SnapshotStore(snapshotV1);

        // Reader captures View from State 1
        ConfigView view1 = new ConfigViewImpl(store.get(), Scope.global());
        assertThat(view1.getDouble("window.start")).isEqualTo(20.0);
        assertThat(view1.getDouble("window.end")).isEqualTo(6.0);

        // Background update advances store to State 2: start=22.0, end=7.0
        OverrideValue newStart = new OverrideValue("window.start", Scope.global(), Level.GLOBAL, 22.0);
        OverrideValue newEnd = new OverrideValue("window.end", Scope.global(), Level.GLOBAL, 7.0);
        Snapshot snapshotV2 = new Snapshot(
                2L,
                Map.of("window.start", startDef, "window.end", endDef),
                Map.of("window.start", Map.of(Scope.global(), newStart), "window.end", Map.of(Scope.global(), newEnd))
        );
        store.updateIfNewer(snapshotV2);

        // Assert: view1 remains completely anchored to State 1! It NEVER observes partial state
        assertThat(view1.getDouble("window.start")).isEqualTo(20.0);
        assertThat(view1.getDouble("window.end")).isEqualTo(6.0);

        // A new view captures State 2
        ConfigView view2 = new ConfigViewImpl(store.get(), Scope.global());
        assertThat(view2.getDouble("window.start")).isEqualTo(22.0);
        assertThat(view2.getDouble("window.end")).isEqualTo(7.0);
    }

    @Test
    @DisplayName("Strongly typed getters resolve numbers, durations, time windows, lists, booleans, and strings")
    void stronglyTypedGetters() {
        SettingDefinition num = new SettingDefinition("k.num", SettingType.NUMBER, "desc", ValidationRule.empty(), 42);
        SettingDefinition dur = new SettingDefinition("k.dur", SettingType.DURATION, "desc", ValidationRule.empty(), Duration.ofMinutes(30));
        SettingDefinition win = new SettingDefinition("k.win", SettingType.TIME_WINDOW, "desc", ValidationRule.empty(), new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 0)));
        SettingDefinition list = new SettingDefinition("k.list", SettingType.LIST, "desc", ValidationRule.empty(), List.of("A", "B"));
        SettingDefinition bool = new SettingDefinition("k.bool", SettingType.ENUM, "desc", ValidationRule.empty(), true);

        Snapshot snapshot = new Snapshot(
                1L,
                Map.of("k.num", num, "k.dur", dur, "k.win", win, "k.list", list, "k.bool", bool),
                Map.of()
        );

        ConfigView view = new ConfigViewImpl(snapshot, Scope.global());

        assertThat(view.getInt("k.num")).isEqualTo(42);
        assertThat(view.getLong("k.num")).isEqualTo(42L);
        assertThat(view.getDouble("k.num")).isEqualTo(42.0);
        assertThat(view.getDuration("k.dur")).isEqualTo(Duration.ofMinutes(30));
        assertThat(view.getTimeWindow("k.win")).isEqualTo(new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 0)));
        assertThat(view.getList("k.list")).containsExactly("A", "B");
        assertThat(view.getBoolean("k.bool")).isTrue();
        assertThat(view.getString("k.num")).isEqualTo("42");
    }
}
