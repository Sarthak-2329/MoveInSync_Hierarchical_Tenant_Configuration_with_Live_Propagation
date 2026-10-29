package com.moveinsync.config.core.model;

import java.io.Serializable;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Validation rules applied when defining or overriding a configuration value.
 */
public record ValidationRule(
        Double minNumber,
        Double maxNumber,
        Duration minDuration,
        Duration maxDuration,
        Set<String> allowedValues,
        Integer maxListSize,
        String regexPattern
) implements Serializable {

    public ValidationRule {
        // Guarantee immutable set for allowedValues
        allowedValues = (allowedValues != null)
                ? Collections.unmodifiableSet(Set.copyOf(allowedValues))
                : Collections.emptySet();

        if (minNumber != null && maxNumber != null && minNumber > maxNumber) {
            throw new IllegalArgumentException("minNumber cannot be greater than maxNumber");
        }
        if (minDuration != null && maxDuration != null && minDuration.compareTo(maxDuration) > 0) {
            throw new IllegalArgumentException("minDuration cannot be greater than maxDuration");
        }
        if (maxListSize != null && maxListSize < 0) {
            throw new IllegalArgumentException("maxListSize cannot be negative");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ValidationRule empty() {
        return new Builder().build();
    }

    public static class Builder {
        private Double minNumber;
        private Double maxNumber;
        private Duration minDuration;
        private Duration maxDuration;
        private Set<String> allowedValues;
        private Integer maxListSize;
        private String regexPattern;

        public Builder minNumber(Double minNumber) {
            this.minNumber = minNumber;
            return this;
        }

        public Builder maxNumber(Double maxNumber) {
            this.maxNumber = maxNumber;
            return this;
        }

        public Builder minDuration(Duration minDuration) {
            this.minDuration = minDuration;
            return this;
        }

        public Builder maxDuration(Duration maxDuration) {
            this.maxDuration = maxDuration;
            return this;
        }

        public Builder allowedValues(Set<String> allowedValues) {
            this.allowedValues = allowedValues;
            return this;
        }

        public Builder maxListSize(Integer maxListSize) {
            this.maxListSize = maxListSize;
            return this;
        }

        public Builder regexPattern(String regexPattern) {
            this.regexPattern = regexPattern;
            return this;
        }

        public ValidationRule build() {
            return new ValidationRule(
                    minNumber, maxNumber, minDuration, maxDuration, allowedValues, maxListSize, regexPattern
            );
        }
    }
}
