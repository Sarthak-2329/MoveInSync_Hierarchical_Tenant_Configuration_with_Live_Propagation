package com.moveinsync.config.core.engine;

import com.moveinsync.config.core.exception.ValidationException;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.core.model.TimeWindow;
import com.moveinsync.config.core.model.ValidationRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueValidatorTest {

    @Nested
    @DisplayName("NUMBER validation")
    class NumberValidation {

        private final SettingDefinition numberDef = new SettingDefinition(
                "max.ride.time",
                SettingType.NUMBER,
                "Maximum ride time in minutes",
                ValidationRule.builder().minNumber(10.0).maxNumber(180.0).build(),
                60.0
        );

        @Test
        @DisplayName("Valid number within bounds is accepted")
        void validNumber() {
            Object result = ValueValidator.validateAndCoerce(numberDef, 90.0);
            assertThat(result).isEqualTo(90.0);

            // Also parses valid string
            Object parsedStr = ValueValidator.validateAndCoerce(numberDef, "120");
            assertThat(parsedStr).isEqualTo(120.0);
        }

        @Test
        @DisplayName("Number below min is rejected")
        void numberBelowMin() {
            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(numberDef, 5.0))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("less than allowed minimum");
        }

        @Test
        @DisplayName("Number above max is rejected")
        void numberAboveMax() {
            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(numberDef, 240.0))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("greater than allowed maximum");
        }

        @Test
        @DisplayName("Non-numeric string is rejected")
        void nonNumericString() {
            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(numberDef, "abc"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Invalid number format");
        }
    }

    @Nested
    @DisplayName("DURATION validation")
    class DurationValidation {

        private final SettingDefinition durationDef = new SettingDefinition(
                "booking.cutoff.duration",
                SettingType.DURATION,
                "Booking cut-off duration",
                ValidationRule.builder().minDuration(Duration.ofMinutes(15)).maxDuration(Duration.ofHours(4)).build(),
                Duration.ofMinutes(60)
        );

        @Test
        @DisplayName("Valid duration representations are accepted and coerced")
        void validDuration() {
            Object res1 = ValueValidator.validateAndCoerce(durationDef, Duration.ofMinutes(30));
            assertThat(res1).isEqualTo(Duration.ofMinutes(30));

            Object res2 = ValueValidator.validateAndCoerce(durationDef, "45m");
            assertThat(res2).isEqualTo(Duration.ofMinutes(45));

            Object res3 = ValueValidator.validateAndCoerce(durationDef, "PT2H");
            assertThat(res3).isEqualTo(Duration.ofHours(2));
        }

        @Test
        @DisplayName("Negative duration is strictly rejected")
        void negativeDurationRejected() {
            // Cut-off of -30 minutes as specified in assignment requirements
            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(durationDef, "-30m"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Duration cannot be negative");

            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(durationDef, Duration.ofMinutes(-30)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Duration cannot be negative");

            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(durationDef, -1800L))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Duration cannot be negative");
        }

        @Test
        @DisplayName("Duration outside min/max boundaries is rejected")
        void durationBoundaries() {
            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(durationDef, "10m"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("less than allowed minimum");

            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(durationDef, "5h"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("greater than allowed maximum");
        }
    }

    @Nested
    @DisplayName("TIME_WINDOW validation")
    class TimeWindowValidation {

        private final SettingDefinition windowDef = new SettingDefinition(
                "night.safety.window",
                SettingType.TIME_WINDOW,
                "Night safety escort window",
                ValidationRule.empty(),
                new TimeWindow(LocalTime.of(20, 0), LocalTime.of(23, 59))
        );

        @Test
        @DisplayName("Valid window with end strictly after start is accepted")
        void validWindow() {
            Object result = ValueValidator.validateAndCoerce(windowDef, "08:00-17:00");
            assertThat(result).isEqualTo(new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 0)));
        }

        @Test
        @DisplayName("Time window where end is before start is strictly rejected")
        void windowEndBeforeStartRejected() {
            // "a window that ends before it starts" requirement
            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(windowDef, "20:00-06:00"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("must be strictly after start time");
        }

        @Test
        @DisplayName("Zero duration time window (start == end) is strictly rejected")
        void zeroDurationWindowRejected() {
            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(windowDef, "12:00-12:00"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("must be strictly after start time");
        }

        @Test
        @DisplayName("Malformed time window format is rejected")
        void malformedWindowRejected() {
            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(windowDef, "invalid-window"))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayName("ENUM validation")
    class EnumValidation {

        private final SettingDefinition enumDef = new SettingDefinition(
                "cab.tier",
                SettingType.ENUM,
                "Vehicle category",
                ValidationRule.builder().allowedValues(Set.of("SEDAN", "SUV", "HATCHBACK")).build(),
                "SEDAN"
        );

        @Test
        @DisplayName("Allowed enum value is accepted")
        void allowedEnum() {
            Object val = ValueValidator.validateAndCoerce(enumDef, "SUV");
            assertThat(val).isEqualTo("SUV");
        }

        @Test
        @DisplayName("Unrecognised enum value is rejected")
        void disallowedEnum() {
            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(enumDef, "SPORTS_CAR"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("not in allowed enum set");
        }
    }

    @Nested
    @DisplayName("LIST validation")
    class ListValidation {

        private final SettingDefinition listDef = new SettingDefinition(
                "allowed.cab.types",
                SettingType.LIST,
                "List of allowed cab types",
                ValidationRule.builder().maxListSize(3).allowedValues(Set.of("SEDAN", "SUV", "HATCHBACK", "VAN")).build(),
                List.of("SEDAN", "HATCHBACK")
        );

        @Test
        @DisplayName("Valid list within size and allowed elements is accepted")
        void validList() {
            Object result = ValueValidator.validateAndCoerce(listDef, List.of("SEDAN", "SUV"));
            assertThat(result).isEqualTo(List.of("SEDAN", "SUV"));

            // CSV parsing
            Object csvResult = ValueValidator.validateAndCoerce(listDef, "SEDAN, SUV, VAN");
            assertThat(csvResult).isEqualTo(List.of("SEDAN", "SUV", "VAN"));
        }

        @Test
        @DisplayName("List exceeding max size is rejected")
        void listExceedingSize() {
            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(listDef, List.of("SEDAN", "SUV", "HATCHBACK", "VAN")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("exceeds max allowed size");
        }

        @Test
        @DisplayName("List with unallowed element is rejected")
        void listWithUnallowedElement() {
            assertThatThrownBy(() -> ValueValidator.validateAndCoerce(listDef, List.of("SEDAN", "HELICOPTER")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("not in allowed set");
        }
    }
}
