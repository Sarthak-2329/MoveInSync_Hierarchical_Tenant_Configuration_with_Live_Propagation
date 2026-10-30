package com.moveinsync.config.client.dto;

import com.moveinsync.config.core.model.ConfigDelta;
import com.moveinsync.config.core.model.ConfigDelta.DeletedOverrideKey;
import com.moveinsync.config.core.model.OverrideValue;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.StagedRollout;

import java.util.Collections;
import java.util.List;

/**
 * DTO representing delta response received from the server's {@code GET /api/config/changes?since={version}}.
 */
public record ClientDeltaResponse(
        long fromVersion,
        long toVersion,
        long currentVersion,
        List<SettingDefinition> updatedDefinitions,
        List<OverrideValue> updatedOverrides,
        List<DeletedOverrideKey> deletedOverrides,
        List<StagedRollout> updatedRollouts
) {
    public ClientDeltaResponse {
        updatedDefinitions = (updatedDefinitions != null) ? updatedDefinitions : Collections.emptyList();
        updatedOverrides = (updatedOverrides != null) ? updatedOverrides : Collections.emptyList();
        deletedOverrides = (deletedOverrides != null) ? deletedOverrides : Collections.emptyList();
        updatedRollouts = (updatedRollouts != null) ? updatedRollouts : Collections.emptyList();
    }

    public ClientDeltaResponse(
            long fromVersion,
            long toVersion,
            long currentVersion,
            List<SettingDefinition> updatedDefinitions,
            List<OverrideValue> updatedOverrides,
            List<DeletedOverrideKey> deletedOverrides
    ) {
        this(fromVersion, toVersion, currentVersion, updatedDefinitions, updatedOverrides, deletedOverrides, Collections.emptyList());
    }

    public ConfigDelta toDomainDelta() {
        return new ConfigDelta(
                fromVersion,
                toVersion,
                updatedDefinitions,
                updatedOverrides,
                deletedOverrides,
                updatedRollouts
        );
    }
}
