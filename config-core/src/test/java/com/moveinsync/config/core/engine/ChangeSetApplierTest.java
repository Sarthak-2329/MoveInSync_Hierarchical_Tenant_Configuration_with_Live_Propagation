package com.moveinsync.config.core.engine;

import com.moveinsync.config.core.exception.ValidationException;
import com.moveinsync.config.core.exception.VersionConflictException;
import com.moveinsync.config.core.model.ChangeItem;
import com.moveinsync.config.core.model.ChangeSet;
import com.moveinsync.config.core.model.ConfigDelta;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.core.model.ValidationRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeSetApplierTest {

    private Snapshot baseSnapshot;

    @BeforeEach
    void setUp() {
        Snapshot empty = Snapshot.empty();

        SettingDefinition cutoff = new SettingDefinition(
                "booking.cutoff",
                SettingType.DURATION,
                "Booking cut-off duration",
                ValidationRule.builder().minDuration(Duration.ofMinutes(15)).build(),
                Duration.ofMinutes(60)
        );

        SettingDefinition maxRide = new SettingDefinition(
                "max.ride.time",
                SettingType.NUMBER,
                "Max ride time in minutes",
                ValidationRule.builder().minNumber(10.0).maxNumber(180.0).build(),
                90.0
        );

        SettingDefinition cabType = new SettingDefinition(
                "allowed.cab.type",
                SettingType.ENUM,
                "Cab category",
                ValidationRule.builder().allowedValues(java.util.Set.of("SEDAN", "SUV")).build(),
                "SEDAN"
        );

        Snapshot s1 = ChangeSetApplier.registerDefinition(empty, cutoff, 1L);
        Snapshot s2 = ChangeSetApplier.registerDefinition(s1, maxRide, 2L);
        baseSnapshot = ChangeSetApplier.registerDefinition(s2, cabType, 3L);
    }

    @Test
    @DisplayName("Atomicity Guarantee: If any item in a ChangeSet is invalid, NOTHING changes")
    void atomicityOnInvalidItem() {
        // ChangeSet contains 2 valid items and 1 invalid item (booking cut-off = -30m)
        ChangeSet invalidBatch = new ChangeSet(
                "batch-fail",
                "operator-1",
                "Update policies",
                List.of(
                        ChangeItem.set("max.ride.time", Scope.client("clientA"), Level.CLIENT, 120.0), // valid
                        ChangeItem.set("allowed.cab.type", Scope.client("clientA"), Level.CLIENT, "SUV"), // valid
                        ChangeItem.set("booking.cutoff", Scope.client("clientA"), Level.CLIENT, "-30m") // INVALID: negative duration!
                ),
                null
        );

        // Act & Assert
        assertThatThrownBy(() -> ChangeSetApplier.applyChangeSet(baseSnapshot, invalidBatch, 4L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Duration cannot be negative");

        // Verify baseSnapshot remains 100% unaltered (version 3, no overrides for clientA)
        assertThat(baseSnapshot.version()).isEqualTo(3L);
        assertThat(baseSnapshot.getOverride("max.ride.time", Scope.client("clientA"))).isNull();
        assertThat(baseSnapshot.getOverride("allowed.cab.type", Scope.client("clientA"))).isNull();
        assertThat(baseSnapshot.getOverride("booking.cutoff", Scope.client("clientA"))).isNull();

        // Reads for clientA still return defaults
        assertThat(Resolver.resolve(baseSnapshot, "max.ride.time", Scope.client("clientA"))).isEqualTo(90.0);
        assertThat(Resolver.resolve(baseSnapshot, "allowed.cab.type", Scope.client("clientA"))).isEqualTo("SEDAN");
    }

    @Test
    @DisplayName("Monotonicity Guarantee: New version must be strictly greater than current version")
    void versionMonotonicity() {
        ChangeSet validBatch = new ChangeSet(
                "batch-1",
                "operator",
                "Update max ride",
                List.of(ChangeItem.set("max.ride.time", Scope.client("clientA"), Level.CLIENT, 120.0)),
                null
        );

        // Attempting to apply same version (3 <= 3)
        assertThatThrownBy(() -> ChangeSetApplier.applyChangeSet(baseSnapshot, validBatch, 3L))
                .isInstanceOf(VersionConflictException.class)
                .hasMessageContaining("versions must strictly increase");

        // Attempting to apply older version (2 < 3)
        assertThatThrownBy(() -> ChangeSetApplier.applyChangeSet(baseSnapshot, validBatch, 2L))
                .isInstanceOf(VersionConflictException.class)
                .hasMessageContaining("versions must strictly increase");

        // Applying newer version (4 > 3) succeeds
        Snapshot next = ChangeSetApplier.applyChangeSet(baseSnapshot, validBatch, 4L);
        assertThat(next.version()).isEqualTo(4L);
        assertThat(Resolver.resolve(next, "max.ride.time", Scope.client("clientA"))).isEqualTo(120.0);
    }

    @Test
    @DisplayName("Scope and Level mismatch is caught and rejected at creation")
    void scopeLevelMismatch() {
        // Attempting to set CLIENT level override with a scope that includes officeId fails fast
        assertThatThrownBy(() -> ChangeItem.set("max.ride.time", Scope.office("clientA", "office1"), Level.CLIENT, 100.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match level CLIENT");
    }

    @Test
    @DisplayName("Deleting an override correctly removes it from the snapshot")
    void deleteOverride() {
        ChangeSet addOverride = new ChangeSet(
                "add-1",
                "operator",
                "Add override",
                List.of(ChangeItem.set("max.ride.time", Scope.client("clientA"), Level.CLIENT, 120.0)),
                null
        );
        Snapshot v4 = ChangeSetApplier.applyChangeSet(baseSnapshot, addOverride, 4L);
        assertThat(Resolver.resolve(v4, "max.ride.time", Scope.client("clientA"))).isEqualTo(120.0);

        ChangeSet deleteOverride = new ChangeSet(
                "del-1",
                "operator",
                "Remove override",
                List.of(ChangeItem.delete("max.ride.time", Scope.client("clientA"), Level.CLIENT)),
                null
        );
        Snapshot v5 = ChangeSetApplier.applyChangeSet(v4, deleteOverride, 5L);

        // Now falls back to definition default (90.0)
        assertThat(Resolver.resolve(v5, "max.ride.time", Scope.client("clientA"))).isEqualTo(90.0);
    }

    @Test
    @DisplayName("Diff computation and Delta application perfectly transitions snapshot state")
    void diffAndDeltaApplication() {
        ChangeSet batch = new ChangeSet(
                "batch-delta",
                "operator",
                "Test delta",
                List.of(
                        ChangeItem.set("max.ride.time", Scope.client("clientA"), Level.CLIENT, 110.0),
                        ChangeItem.set("allowed.cab.type", Scope.client("clientA"), Level.CLIENT, "SUV")
                ),
                null
        );
        Snapshot targetSnapshot = ChangeSetApplier.applyChangeSet(baseSnapshot, batch, 4L);

        // Compute diff
        ConfigDelta delta = SnapshotDiffCalculator.calculateDiff(baseSnapshot, targetSnapshot);
        assertThat(delta.fromVersion()).isEqualTo(3L);
        assertThat(delta.toVersion()).isEqualTo(4L);
        assertThat(delta.updatedOverrides()).hasSize(2);

        // Apply delta to base snapshot
        Snapshot reconstructed = ChangeSetApplier.applyDelta(baseSnapshot, delta);
        assertThat(reconstructed.version()).isEqualTo(4L);
        assertThat(Resolver.resolve(reconstructed, "max.ride.time", Scope.client("clientA"))).isEqualTo(110.0);
        assertThat(Resolver.resolve(reconstructed, "allowed.cab.type", Scope.client("clientA"))).isEqualTo("SUV");
    }
}
