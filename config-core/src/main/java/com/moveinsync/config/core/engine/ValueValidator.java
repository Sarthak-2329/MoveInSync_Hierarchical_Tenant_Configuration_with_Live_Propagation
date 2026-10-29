package com.moveinsync.config.core.engine;

import com.moveinsync.config.core.exception.ValidationException;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.core.model.TimeWindow;
import com.moveinsync.config.core.model.ValidationRule;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validates and coerces configuration values against setting definitions and validation rules.
 *
 * Enforces business constraints such as:
 * - Negative durations are strictly rejected (e.g. cut-off of -30 minutes).
 * - Time windows where end is not strictly after start are rejected.
 * - Out-of-bounds numbers and invalid enum members are rejected.
 */
public final class ValueValidator {

    private ValueValidator() {
        // Utility class
    }

    /**
     * Validates and normalises a value against the schema definition.
     *
     * @param definition the setting definition containing type and rules
     * @param rawValue the raw value to validate
     * @return the coerced, strongly-typed value
     * @throws ValidationException if the value violates type or constraint rules
     */
    public static Object validateAndCoerce(SettingDefinition definition, Object rawValue) {
        Objects.requireNonNull(definition, "definition must not be null");
        if (rawValue == null) {
            throw new ValidationException("Value cannot be null for setting '" + definition.key() + "'");
        }

        SettingType type = definition.type();
        ValidationRule rule = definition.validationRule();

        return switch (type) {
            case NUMBER -> validateNumber(definition.key(), rawValue, rule);
            case DURATION -> validateDuration(definition.key(), rawValue, rule);
            case TIME_WINDOW -> validateTimeWindow(definition.key(), rawValue);
            case ENUM -> validateEnum(definition.key(), rawValue, rule);
            case LIST -> validateList(definition.key(), rawValue, rule);
        };
    }

    private static Double validateNumber(String key, Object raw, ValidationRule rule) {
        double val;
        if (raw instanceof Number number) {
            val = number.doubleValue();
        } else if (raw instanceof String str) {
            try {
                val = Double.parseDouble(str.trim());
            } catch (NumberFormatException e) {
                throw new ValidationException("Invalid number format for setting '" + key + "': " + raw);
            }
        } else {
            throw new ValidationException("Expected numeric value for setting '" + key + "', got: " + raw.getClass().getSimpleName());
        }

        if (rule.minNumber() != null && val < rule.minNumber()) {
            throw new ValidationException(String.format(
                    "Setting '%s' value %.2f is less than allowed minimum %.2f", key, val, rule.minNumber()));
        }
        if (rule.maxNumber() != null && val > rule.maxNumber()) {
            throw new ValidationException(String.format(
                    "Setting '%s' value %.2f is greater than allowed maximum %.2f", key, val, rule.maxNumber()));
        }
        return val;
    }

    private static Duration validateDuration(String key, Object raw, ValidationRule rule) {
        Duration duration;
        if (raw instanceof Duration d) {
            duration = d;
        } else if (raw instanceof Number seconds) {
            long s = seconds.longValue();
            if (s < 0) {
                throw new ValidationException("Duration cannot be negative for setting '" + key + "': " + s + "s");
            }
            duration = Duration.ofSeconds(s);
        } else if (raw instanceof String str) {
            duration = parseDurationString(key, str.trim());
        } else {
            throw new ValidationException("Expected Duration for setting '" + key + "', got: " + raw.getClass().getSimpleName());
        }

        if (duration.isNegative()) {
            throw new ValidationException("Duration cannot be negative for setting '" + key + "', got: " + duration);
        }

        if (rule.minDuration() != null && duration.compareTo(rule.minDuration()) < 0) {
            throw new ValidationException(String.format(
                    "Setting '%s' duration %s is less than allowed minimum %s", key, duration, rule.minDuration()));
        }
        if (rule.maxDuration() != null && duration.compareTo(rule.maxDuration()) > 0) {
            throw new ValidationException(String.format(
                    "Setting '%s' duration %s is greater than allowed maximum %s", key, duration, rule.maxDuration()));
        }

        return duration;
    }

    private static Duration parseDurationString(String key, String text) {
        // Standard ISO-8601 duration (e.g. PT30M, PT1H, PT-10M)
        if (text.startsWith("P") || text.startsWith("p") || text.startsWith("-P") || text.startsWith("-p")) {
            try {
                Duration d = Duration.parse(text);
                if (d.isNegative()) {
                    throw new ValidationException("Duration cannot be negative for setting '" + key + "': " + text);
                }
                return d;
            } catch (DateTimeParseException ex) {
                throw new ValidationException("Invalid ISO duration for setting '" + key + "': " + text, ex);
            }
        }

        // Shorthand format: 30m, 1h, 45s, -30m
        boolean isNegative = text.startsWith("-");
        if (isNegative) {
            throw new ValidationException("Duration cannot be negative for setting '" + key + "': " + text);
        }

        String lower = text.toLowerCase();
        try {
            if (lower.endsWith("m") && !lower.endsWith("ms")) {
                long minutes = Long.parseLong(lower.substring(0, lower.length() - 1).trim());
                return Duration.ofMinutes(minutes);
            } else if (lower.endsWith("h")) {
                long hours = Long.parseLong(lower.substring(0, lower.length() - 1).trim());
                return Duration.ofHours(hours);
            } else if (lower.endsWith("s")) {
                long seconds = Long.parseLong(lower.substring(0, lower.length() - 1).trim());
                return Duration.ofSeconds(seconds);
            } else if (lower.endsWith("d")) {
                long days = Long.parseLong(lower.substring(0, lower.length() - 1).trim());
                return Duration.ofDays(days);
            } else {
                // Default to minutes if plain integer provided
                long minutes = Long.parseLong(lower);
                return Duration.ofMinutes(minutes);
            }
        } catch (NumberFormatException ex) {
            throw new ValidationException("Invalid duration shorthand format for setting '" + key + "': " + text, ex);
        }
    }

    private static TimeWindow validateTimeWindow(String key, Object raw) {
        if (raw instanceof TimeWindow tw) {
            return tw;
        }
        if (raw instanceof String str) {
            try {
                return TimeWindow.parse(str);
            } catch (IllegalArgumentException ex) {
                throw new ValidationException("Invalid time window for setting '" + key + "': " + ex.getMessage(), ex);
            }
        }
        throw new ValidationException("Expected TimeWindow or 'HH:mm-HH:mm' string for setting '" + key + "', got: " + raw.getClass().getSimpleName());
    }

    private static String validateEnum(String key, Object raw, ValidationRule rule) {
        String val = raw.toString().trim();
        if (rule.allowedValues() != null && !rule.allowedValues().isEmpty()) {
            if (!rule.allowedValues().contains(val)) {
                throw new ValidationException(String.format(
                        "Setting '%s' value '%s' is not in allowed enum set: %s", key, val, rule.allowedValues()));
            }
        }
        return val;
    }

    private static List<String> validateList(String key, Object raw, ValidationRule rule) {
        List<String> list = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    list.add(item.toString());
                }
            }
        } else if (raw instanceof String str) {
            if (!str.isBlank()) {
                String[] parts = str.split(",");
                for (String part : parts) {
                    list.add(part.trim());
                }
            }
        } else if (raw instanceof Object[] arr) {
            for (Object item : arr) {
                if (item != null) {
                    list.add(item.toString());
                }
            }
        } else {
            throw new ValidationException("Expected Collection or CSV string for LIST setting '" + key + "', got: " + raw.getClass().getSimpleName());
        }

        if (rule.maxListSize() != null && list.size() > rule.maxListSize()) {
            throw new ValidationException(String.format(
                    "Setting '%s' list size %d exceeds max allowed size %d", key, list.size(), rule.maxListSize()));
        }

        if (rule.allowedValues() != null && !rule.allowedValues().isEmpty()) {
            for (String item : list) {
                if (!rule.allowedValues().contains(item)) {
                    throw new ValidationException(String.format(
                            "Setting '%s' list item '%s' is not in allowed set: %s", key, item, rule.allowedValues()));
                }
            }
        }

        return List.copyOf(list);
    }
}
