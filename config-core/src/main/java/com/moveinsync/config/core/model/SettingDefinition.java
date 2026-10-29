package com.moveinsync.config.core.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Master definition for a configuration setting key.
 *
 * Defines the canonical key name, data type, human-readable description,
 * validation constraints, and the mandatory global default value.
 */
public record SettingDefinition(
        String key,
        SettingType type,
        String description,
        ValidationRule validationRule,
        Object defaultValue
) implements Serializable {

    public SettingDefinition {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Setting key must not be blank");
        }
        Objects.requireNonNull(type, "SettingType must not be null for key: " + key);
        Objects.requireNonNull(defaultValue, "defaultValue must not be null for key: " + key);
        validationRule = (validationRule != null) ? validationRule : ValidationRule.empty();
    }
}
