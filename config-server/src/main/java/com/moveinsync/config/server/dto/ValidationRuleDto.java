package com.moveinsync.config.server.dto;

import com.moveinsync.config.core.model.ValidationRule;

import java.time.Duration;
import java.util.Set;

public record ValidationRuleDto(
        Double minNumber,
        Double maxNumber,
        String minDuration,
        String maxDuration,
        Set<String> allowedValues,
        Integer maxListSize,
        String regexPattern
) {
    public ValidationRule toDomain() {
        ValidationRule.Builder builder = ValidationRule.builder()
                .minNumber(minNumber)
                .maxNumber(maxNumber)
                .allowedValues(allowedValues)
                .maxListSize(maxListSize)
                .regexPattern(regexPattern);

        if (minDuration != null && !minDuration.isBlank()) {
            builder.minDuration(parseDuration(minDuration));
        }
        if (maxDuration != null && !maxDuration.isBlank()) {
            builder.maxDuration(parseDuration(maxDuration));
        }
        return builder.build();
    }

    private static Duration parseDuration(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("P") || trimmed.startsWith("p")) {
            return Duration.parse(trimmed);
        }
        if (trimmed.toLowerCase().endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
        }
        if (trimmed.toLowerCase().endsWith("h")) {
            return Duration.ofHours(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
        }
        if (trimmed.toLowerCase().endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
        }
        return Duration.ofMinutes(Long.parseLong(trimmed));
    }
}
