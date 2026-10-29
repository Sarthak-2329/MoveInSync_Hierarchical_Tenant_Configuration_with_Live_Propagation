package com.moveinsync.config.server.dto;

import com.moveinsync.config.core.model.ConfigDelta;
import com.moveinsync.config.core.model.ConfigDelta.DeletedOverrideKey;
import com.moveinsync.config.core.model.OverrideValue;
import com.moveinsync.config.core.model.SettingDefinition;

import java.util.List;

/**
 * Payload returned by GET /api/config/changes?since={v}.
 *
 * Notice: currentVersion is always returned alongside the delta.
 * If a tenant has no updates between version 10 and 15, updatedOverrides
 * will be empty, but currentVersion is 15 so the client can advance its pointer.
 */
public record DeltaResponse(
        long fromVersion,
        long toVersion,
        long currentVersion,
        List<SettingDefinition> updatedDefinitions,
        List<OverrideValue> updatedOverrides,
        List<DeletedOverrideKey> deletedOverrides
) {
    public static DeltaResponse from(ConfigDelta delta, long currentVersion) {
        return new DeltaResponse(
                delta.fromVersion(),
                delta.toVersion(),
                currentVersion,
                delta.updatedDefinitions(),
                delta.updatedOverrides(),
                delta.deletedOverrides()
        );
    }
}
