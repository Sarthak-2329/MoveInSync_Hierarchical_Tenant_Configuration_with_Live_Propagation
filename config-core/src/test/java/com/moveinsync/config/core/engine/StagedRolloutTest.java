package com.moveinsync.config.core.engine;

import com.moveinsync.config.core.exception.ValidationException;
import com.moveinsync.config.core.model.ChangeItem;
import com.moveinsync.config.core.model.ChangeSet;
import com.moveinsync.config.core.model.ConfigDelta;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.ResolutionExplanation;
import com.moveinsync.config.core.model.RolloutStatus;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.core.model.StagedRollout;
import com.moveinsync.config.core.model.TimeWindow;
import com.moveinsync.config.core.model.ValidationRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagedRolloutTest {

    private Snapshot baseSnapshot;
    private final String SETTING_KEY = "night.safety.window";
    private final TimeWindow DEFAULT_WINDOW = new TimeWindow(LocalTime.of(18, 0), LocalTime.of(23, 0));
    private final TimeWindow CANARY_WINDOW = new TimeWindow(LocalTime.of(19, 0), LocalTime.of(23, 0));

    @BeforeEach
    void setUp() {
        SettingDefinition def = new SettingDefinition(
                SETTING_KEY,
                SettingType.TIME_WINDOW,
                "Night safety escort required window",
                ValidationRule.builder().build(),
                DEFAULT_WINDOW
        );
        baseSnapshot = ChangeSetApplier.registerDefinition(Snapshot.empty(), def, 1L);
    }

    @Test
    @DisplayName("Active staged rollout applies to designated canary office while other offices see baseline")
    void stagedRolloutCanaryIsolation() {
        StagedRollout rollout = new StagedRollout(
                "ro-1",
                SETTING_KEY,
                "google",
                Set.of("bangalore"),
                Level.CLIENT,
                CANARY_WINDOW,
                RolloutStatus.ACTIVE,
                "sarthak",
                "Testing 20:00 night window in Bangalore",
                Instant.now(),
                2L
        );

        Snapshot stagedSnapshot = ChangeSetApplier.applyStagedRollout(baseSnapshot, rollout, 2L);

        // Bangalore is in the canary set -> receives 20:00-06:00
        Object blrVal = Resolver.resolve(stagedSnapshot, SETTING_KEY, Scope.office("google", "bangalore"));
        assertThat(blrVal).isEqualTo(CANARY_WINDOW);

        // Hyderabad is NOT in canary set -> receives schema default 22:00-06:00
        Object hydVal = Resolver.resolve(stagedSnapshot, SETTING_KEY, Scope.office("google", "hyderabad"));
        assertThat(hydVal).isEqualTo(DEFAULT_WINDOW);

        // Unscoped or client-wide query -> receives schema default
        Object clientVal = Resolver.resolve(stagedSnapshot, SETTING_KEY, Scope.client("google"));
        assertThat(clientVal).isEqualTo(DEFAULT_WINDOW);
    }

    @Test
    @DisplayName("SHIFT override still takes precedence over canary office staged rollout")
    void shiftOverrideBeatsCanaryOffice() {
        TimeWindow shiftWindow = new TimeWindow(LocalTime.of(20, 0), LocalTime.of(23, 0));

        // Add a SHIFT level override for google/bangalore/night
        ChangeSet shiftChange = new ChangeSet(
                "cs-shift",
                "sarthak",
                "Shift override",
                List.of(ChangeItem.set(SETTING_KEY, Scope.shift("google", "bangalore", "night"), Level.SHIFT, shiftWindow)),
                Instant.now()
        );
        Snapshot withShift = ChangeSetApplier.applyChangeSet(baseSnapshot, shiftChange, 2L);

        // Stage canary rollout for Bangalore
        StagedRollout rollout = new StagedRollout(
                "ro-1",
                SETTING_KEY,
                "google",
                Set.of("bangalore"),
                Level.CLIENT,
                CANARY_WINDOW,
                RolloutStatus.ACTIVE,
                "sarthak",
                "Testing canary",
                Instant.now(),
                3L
        );
        Snapshot stagedSnapshot = ChangeSetApplier.applyStagedRollout(withShift, rollout, 3L);

        // Query for night shift in bangalore -> SHIFT level wins (19:00 - 07:00)
        Object resolvedShift = Resolver.resolve(stagedSnapshot, SETTING_KEY, Scope.shift("google", "bangalore", "night"));
        assertThat(resolvedShift).isEqualTo(shiftWindow);

        // Query for day shift in bangalore (no shift override) -> Canary office staged rollout wins (20:00 - 06:00)
        Object resolvedOffice = Resolver.resolve(stagedSnapshot, SETTING_KEY, Scope.office("google", "bangalore"));
        assertThat(resolvedOffice).isEqualTo(CANARY_WINDOW);
    }

    @Test
    @DisplayName("Promoting a staged rollout converts it to permanent CLIENT override across all offices")
    void promoteRolloutToClientWide() {
        StagedRollout rollout = new StagedRollout(
                "ro-1",
                SETTING_KEY,
                "google",
                Set.of("bangalore"),
                Level.CLIENT,
                CANARY_WINDOW,
                RolloutStatus.ACTIVE,
                "sarthak",
                "Canary BLR",
                Instant.now(),
                2L
        );
        Snapshot stagedSnapshot = ChangeSetApplier.applyStagedRollout(baseSnapshot, rollout, 2L);

        // Promote rollout at version 3
        Snapshot promotedSnapshot = ChangeSetApplier.promoteStagedRollout(
                stagedSnapshot, "ro-1", 3L, "sarthak", "Canary verified, promoting company-wide");

        // Verify status is PROMOTED
        StagedRollout promotedRollout = promotedSnapshot.getStagedRollout("ro-1");
        assertThat(promotedRollout.status()).isEqualTo(RolloutStatus.PROMOTED);

        // Now ALL google offices resolve CANARY_WINDOW (20:00 - 06:00)
        assertThat(Resolver.resolve(promotedSnapshot, SETTING_KEY, Scope.office("google", "bangalore"))).isEqualTo(CANARY_WINDOW);
        assertThat(Resolver.resolve(promotedSnapshot, SETTING_KEY, Scope.office("google", "hyderabad"))).isEqualTo(CANARY_WINDOW);
        assertThat(Resolver.resolve(promotedSnapshot, SETTING_KEY, Scope.office("google", "pune"))).isEqualTo(CANARY_WINDOW);
        assertThat(Resolver.resolve(promotedSnapshot, SETTING_KEY, Scope.client("google"))).isEqualTo(CANARY_WINDOW);

        // Other tenants (e.g. uber) remain completely unaffected
        assertThat(Resolver.resolve(promotedSnapshot, SETTING_KEY, Scope.client("uber"))).isEqualTo(DEFAULT_WINDOW);
    }

    @Test
    @DisplayName("Discarding a staged rollout immediately reverts canary office to baseline")
    void discardRolloutRevertsCanary() {
        StagedRollout rollout = new StagedRollout(
                "ro-1",
                SETTING_KEY,
                "google",
                Set.of("bangalore"),
                Level.CLIENT,
                CANARY_WINDOW,
                RolloutStatus.ACTIVE,
                "sarthak",
                "Canary BLR",
                Instant.now(),
                2L
        );
        Snapshot stagedSnapshot = ChangeSetApplier.applyStagedRollout(baseSnapshot, rollout, 2L);
        assertThat(Resolver.resolve(stagedSnapshot, SETTING_KEY, Scope.office("google", "bangalore"))).isEqualTo(CANARY_WINDOW);

        // Discard rollout at version 3
        Snapshot discardedSnapshot = ChangeSetApplier.discardStagedRollout(
                stagedSnapshot, "ro-1", 3L, "sarthak", "Issues observed; aborting canary");

        assertThat(discardedSnapshot.getStagedRollout("ro-1").status()).isEqualTo(RolloutStatus.DISCARDED);

        // Bangalore immediately reverts to default
        assertThat(Resolver.resolve(discardedSnapshot, SETTING_KEY, Scope.office("google", "bangalore"))).isEqualTo(DEFAULT_WINDOW);
    }

    @Test
    @DisplayName("Duplicate active rollout for same key and tenant is rejected")
    void duplicateActiveRolloutRejected() {
        StagedRollout rollout1 = new StagedRollout(
                "ro-1",
                SETTING_KEY,
                "google",
                Set.of("bangalore"),
                Level.CLIENT,
                CANARY_WINDOW,
                RolloutStatus.ACTIVE,
                "sarthak",
                "Canary 1",
                Instant.now(),
                2L
        );
        Snapshot staged = ChangeSetApplier.applyStagedRollout(baseSnapshot, rollout1, 2L);

        StagedRollout rollout2 = new StagedRollout(
                "ro-2",
                SETTING_KEY,
                "google",
                Set.of("hyderabad"),
                Level.CLIENT,
                CANARY_WINDOW,
                RolloutStatus.ACTIVE,
                "sarthak",
                "Canary 2",
                Instant.now(),
                3L
        );

        assertThatThrownBy(() -> ChangeSetApplier.applyStagedRollout(staged, rollout2, 3L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("An active staged rollout already exists");
    }

    @Test
    @DisplayName("Explain mode details active staged rollout evaluation on canary office")
    void explainModeReflectsCanaryRollout() {
        StagedRollout rollout = new StagedRollout(
                "ro-1",
                SETTING_KEY,
                "google",
                Set.of("bangalore"),
                Level.CLIENT,
                CANARY_WINDOW,
                RolloutStatus.ACTIVE,
                "sarthak",
                "Canary BLR",
                Instant.now(),
                2L
        );
        Snapshot staged = ChangeSetApplier.applyStagedRollout(baseSnapshot, rollout, 2L);

        ResolutionExplanation explanation = Resolver.explain(staged, SETTING_KEY, Scope.office("google", "bangalore"));
        assertThat(explanation.winningValue()).isEqualTo(CANARY_WINDOW);
        assertThat(explanation.evaluations()).anyMatch(e ->
                e.reason().contains("Active Staged Rollout") && e.isWinner());
    }

    @Test
    @DisplayName("Incremental delta computation and application propagates staged rollouts correctly")
    void deltaPropagationIncludesRollouts() {
        StagedRollout rollout = new StagedRollout(
                "ro-1",
                SETTING_KEY,
                "google",
                Set.of("bangalore"),
                Level.CLIENT,
                CANARY_WINDOW,
                RolloutStatus.ACTIVE,
                "sarthak",
                "Canary BLR",
                Instant.now(),
                2L
        );
        Snapshot staged = ChangeSetApplier.applyStagedRollout(baseSnapshot, rollout, 2L);

        ConfigDelta delta = SnapshotDiffCalculator.calculateDiff(baseSnapshot, staged);
        assertThat(delta.updatedRollouts()).hasSize(1);
        assertThat(delta.updatedRollouts().get(0).id()).isEqualTo("ro-1");

        // Client applies delta to baseSnapshot
        Snapshot clientSnapshot = ChangeSetApplier.applyDelta(baseSnapshot, delta);
        assertThat(clientSnapshot.version()).isEqualTo(2L);
        assertThat(Resolver.resolve(clientSnapshot, SETTING_KEY, Scope.office("google", "bangalore"))).isEqualTo(CANARY_WINDOW);
    }
}
