package com.moveinsync.config.server.service;

import com.moveinsync.config.core.model.ConfigDelta;
import com.moveinsync.config.core.model.ConfigDelta.DeletedOverrideKey;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.OverrideValue;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.Snapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enforces strict multi-tenant data isolation.
 *
 * Guarantees:
 * - A tenant NEVER receives override data belonging to another tenant.
 * - Every tenant receives all GLOBAL definitions and GLOBAL overrides.
 * - Scoped overrides (CLIENT, OFFICE, SHIFT) are stripped unless they match the caller's tenantId.
 */
@Service
public class TenantFilterService {

    /**
     * Filters a complete Snapshot to ensure tenant isolation.
     *
     * @param snapshot the master global snapshot
     * @param tenantId the authenticated tenant's ID (or null for unrestricted admin access)
     * @return tenant-isolated Snapshot view
     */
    public Snapshot filterSnapshot(Snapshot snapshot, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return snapshot;
        }

        Map<String, Map<Scope, OverrideValue>> filteredOverrides = new HashMap<>();

        for (Map.Entry<String, Map<Scope, OverrideValue>> keyEntry : snapshot.overridesByKey().entrySet()) {
            Map<Scope, OverrideValue> scopedMatches = new HashMap<>();
            for (Map.Entry<Scope, OverrideValue> scopeEntry : keyEntry.getValue().entrySet()) {
                Scope scope = scopeEntry.getKey();
                OverrideValue ov = scopeEntry.getValue();

                // Allow GLOBAL overrides or overrides strictly matching this tenant
                if (ov.level() == Level.GLOBAL || tenantId.equals(scope.clientId())) {
                    scopedMatches.put(scope, ov);
                }
            }
            if (!scopedMatches.isEmpty()) {
                filteredOverrides.put(keyEntry.getKey(), scopedMatches);
            }
        }

        return new Snapshot(snapshot.version(), snapshot.definitions(), filteredOverrides);
    }

    /**
     * Filters an incremental ConfigDelta to prevent cross-tenant override leakage.
     *
     * @param delta raw delta computed from global change log
     * @param tenantId the authenticated tenant's ID (or null for unrestricted admin access)
     * @return tenant-isolated ConfigDelta
     */
    public ConfigDelta filterDelta(ConfigDelta delta, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return delta;
        }

        List<OverrideValue> filteredUpdates = new ArrayList<>();
        for (OverrideValue ov : delta.updatedOverrides()) {
            if (ov.level() == Level.GLOBAL || tenantId.equals(ov.scope().clientId())) {
                filteredUpdates.add(ov);
            }
        }

        List<DeletedOverrideKey> filteredDeletes = new ArrayList<>();
        for (DeletedOverrideKey del : delta.deletedOverrides()) {
            if (del.level() == Level.GLOBAL || tenantId.equals(del.scope().clientId())) {
                filteredDeletes.add(del);
            }
        }

        return new ConfigDelta(
                delta.fromVersion(),
                delta.toVersion(),
                delta.updatedDefinitions(),
                filteredUpdates,
                filteredDeletes
        );
    }
}
