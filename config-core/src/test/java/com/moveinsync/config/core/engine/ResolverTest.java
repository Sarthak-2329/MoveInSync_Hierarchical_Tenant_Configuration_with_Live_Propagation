package com.moveinsync.config.core.engine;

import com.moveinsync.config.core.exception.SettingNotFoundException;
import com.moveinsync.config.core.model.ChangeItem;
import com.moveinsync.config.core.model.ChangeSet;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.ResolutionExplanation;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.core.model.TimeWindow;
import com.moveinsync.config.core.model.ValidationRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolverTest {

    private Snapshot snapshot;

    @BeforeEach
    void setUp() {
        Snapshot empty = Snapshot.empty();

        // Register setting: booking_cutoff_minutes (NUMBER, default: 60)
        SettingDefinition cutoffDef = new SettingDefinition(
                "booking.cutoff.minutes",
                SettingType.NUMBER,
                "Cut-off time for booking requests in minutes",
                ValidationRule.builder().minNumber(0.0).maxNumber(180.0).build(),
                60.0
        );

        // Register setting: night_window (TIME_WINDOW, default: 20:00-06:00 is invalid in assignment; use 09:00-18:00 default)
        SettingDefinition windowDef = new SettingDefinition(
                "office.working.hours",
                SettingType.TIME_WINDOW,
                "Office working hours",
                ValidationRule.empty(),
                new TimeWindow(LocalTime.of(9, 0), LocalTime.of(18, 0))
        );

        // Register setting: escort_required (ENUM, default: NEVER)
        SettingDefinition escortDef = new SettingDefinition(
                "escort.rule",
                SettingType.ENUM,
                "Escort rule for cab rides",
                ValidationRule.builder().allowedValues(java.util.Set.of("NEVER", "NIGHT_ONLY", "ALWAYS")).build(),
                "NEVER"
        );

        Snapshot v1 = ChangeSetApplier.registerDefinition(empty, cutoffDef, 1L);
        Snapshot v2 = ChangeSetApplier.registerDefinition(v1, windowDef, 2L);
        Snapshot v3 = ChangeSetApplier.registerDefinition(v2, escortDef, 3L);

        // Populate overrides across different levels for client "google":
        // 1. GLOBAL override for booking.cutoff.minutes: 50.0
        // 2. CLIENT override for "google": 45.0
        // 3. OFFICE override for "google", "bangalore": 30.0
        // 4. SHIFT override for "google", "bangalore", "night": 15.0
        ChangeSet changeSet = new ChangeSet(
                "cs-1",
                "admin",
                "Seed hierarchical overrides",
                List.of(
                        ChangeItem.set("booking.cutoff.minutes", Scope.global(), Level.GLOBAL, 50.0),
                        ChangeItem.set("booking.cutoff.minutes", Scope.client("google"), Level.CLIENT, 45.0),
                        ChangeItem.set("booking.cutoff.minutes", Scope.office("google", "bangalore"), Level.OFFICE, 30.0),
                        ChangeItem.set("booking.cutoff.minutes", Scope.shift("google", "bangalore", "night"), Level.SHIFT, 15.0),
                        ChangeItem.set("escort.rule", Scope.client("google"), Level.CLIENT, "NIGHT_ONLY")
                ),
                null
        );

        snapshot = ChangeSetApplier.applyChangeSet(v3, changeSet, 4L);
    }

    @Test
    @DisplayName("Fallback to default value when no overrides exist at all")
    void fallbackToDefault() {
        // Querying for a client with no overrides on office.working.hours
        Object value = Resolver.resolve(snapshot, "office.working.hours", Scope.client("uber"));
        assertThat(value).isEqualTo(new TimeWindow(LocalTime.of(9, 0), LocalTime.of(18, 0)));
    }

    @Test
    @DisplayName("GLOBAL override wins when no client/office/shift override exists")
    void globalOverrideWins() {
        // Query for another tenant that has no client-level override
        Object value = Resolver.resolve(snapshot, "booking.cutoff.minutes", Scope.client("uber"));
        assertThat(value).isEqualTo(50.0);
    }

    @Test
    @DisplayName("CLIENT override wins over GLOBAL override and default")
    void clientOverrideWins() {
        // "google" at Hyderabad office (which has no office override) should get CLIENT override = 45.0
        Object value = Resolver.resolve(snapshot, "booking.cutoff.minutes", Scope.office("google", "hyderabad"));
        assertThat(value).isEqualTo(45.0);
    }

    @Test
    @DisplayName("OFFICE override wins over CLIENT, GLOBAL, and default")
    void officeOverrideWins() {
        // "google" at Bangalore office (morning shift with no shift override) gets OFFICE override = 30.0
        Object value = Resolver.resolve(snapshot, "booking.cutoff.minutes", Scope.shift("google", "bangalore", "morning"));
        assertThat(value).isEqualTo(30.0);
    }

    @Test
    @DisplayName("SHIFT override is the most specific and wins over all other levels")
    void shiftOverrideWins() {
        // "google" at Bangalore night shift gets SHIFT override = 15.0
        Object value = Resolver.resolve(snapshot, "booking.cutoff.minutes", Scope.shift("google", "bangalore", "night"));
        assertThat(value).isEqualTo(15.0);
    }

    @Test
    @DisplayName("Throws SettingNotFoundException when key is unknown")
    void unknownKeyThrows() {
        assertThatThrownBy(() -> Resolver.resolve(snapshot, "non.existent.key", Scope.global()))
                .isInstanceOf(SettingNotFoundException.class)
                .hasMessageContaining("non.existent.key");
    }

    @Test
    @DisplayName("Explain mode details all evaluated levels and highlights the winner")
    void explainAuditTrail() {
        Scope queryScope = Scope.shift("google", "bangalore", "night");
        ResolutionExplanation explanation = Resolver.explain(snapshot, "booking.cutoff.minutes", queryScope);

        assertThat(explanation.key()).isEqualTo("booking.cutoff.minutes");
        assertThat(explanation.queryScope()).isEqualTo(queryScope);
        assertThat(explanation.winningLevel()).isEqualTo(Level.SHIFT);
        assertThat(explanation.winningValue()).isEqualTo(15.0);

        List<ResolutionExplanation.CandidateEvaluation> evals = explanation.evaluations();
        assertThat(evals).hasSize(5);

        // Check SHIFT evaluation
        assertThat(evals.get(0).level()).isEqualTo(Level.SHIFT);
        assertThat(evals.get(0).hasValue()).isTrue();
        assertThat(evals.get(0).isWinner()).isTrue();
        assertThat(evals.get(0).candidateValue()).isEqualTo(15.0);

        // Check OFFICE evaluation (present but shadowed)
        assertThat(evals.get(1).level()).isEqualTo(Level.OFFICE);
        assertThat(evals.get(1).hasValue()).isTrue();
        assertThat(evals.get(1).isWinner()).isFalse();

        // Check CLIENT evaluation (present but shadowed)
        assertThat(evals.get(2).level()).isEqualTo(Level.CLIENT);
        assertThat(evals.get(2).hasValue()).isTrue();
        assertThat(evals.get(2).isWinner()).isFalse();

        // Check GLOBAL override evaluation (present but shadowed)
        assertThat(evals.get(3).level()).isEqualTo(Level.GLOBAL);
        assertThat(evals.get(3).hasValue()).isTrue();
        assertThat(evals.get(3).isWinner()).isFalse();

        // Check DEFAULT evaluation (shadowed)
        assertThat(evals.get(4).isWinner()).isFalse();
    }
}
