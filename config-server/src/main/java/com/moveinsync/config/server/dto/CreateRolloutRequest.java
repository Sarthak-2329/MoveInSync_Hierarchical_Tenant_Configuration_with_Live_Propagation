package com.moveinsync.config.server.dto;

import com.moveinsync.config.core.model.Level;

import java.util.Objects;
import java.util.Set;

/**
 * Request payload to create a new staged configuration rollout.
 */
public record CreateRolloutRequest(
        String id,
        String settingKey,
        String targetClientId,
        Set<String> canaryOfficeIds,
        Level targetLevel,
        Object stagedValue,
        String author,
        String reason
) {
    public CreateRolloutRequest {
        if (settingKey == null || settingKey.isBlank()) {
            throw new IllegalArgumentException("settingKey must not be blank");
        }
        if (canaryOfficeIds == null || canaryOfficeIds.isEmpty()) {
            throw new IllegalArgumentException("canaryOfficeIds must contain at least one office");
        }
        Objects.requireNonNull(targetLevel, "targetLevel must not be null");
        Objects.requireNonNull(stagedValue, "stagedValue must not be null");
    }
}
