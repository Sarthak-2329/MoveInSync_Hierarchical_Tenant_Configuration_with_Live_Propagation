package com.moveinsync.config.server.dto;

import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingType;

public record CreateDefinitionRequest(
        String key,
        SettingType type,
        String description,
        ValidationRuleDto validationRule,
        Object defaultValue,
        String author,
        String reason
) {
    public SettingDefinition toDomain() {
        return new SettingDefinition(
                key,
                type,
                description,
                (validationRule != null) ? validationRule.toDomain() : null,
                defaultValue
        );
    }
}
