package com.moveinsync.config.server.controller;

import com.moveinsync.config.core.model.ConfigDelta;
import com.moveinsync.config.core.model.ResolutionExplanation;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.server.dto.DeltaResponse;
import com.moveinsync.config.server.security.TenantSecurityEnforcer;
import com.moveinsync.config.server.service.ConfigEngineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for querying snapshots, deltas, diffs, and resolution explanations.
 *
 * <p>Multi-Tenant Isolation:
 * For non-ADMIN roles, queries are strictly pinned to the caller's assigned tenant.
 * Any attempt by an OPERATOR or VIEWER to query another tenant's data yields 403 Forbidden.
 */
@RestController
@RequestMapping("/api")
public class ConfigQueryController {

    private final ConfigEngineService configEngineService;
    private final TenantSecurityEnforcer securityEnforcer;

    public ConfigQueryController(
            ConfigEngineService configEngineService,
            TenantSecurityEnforcer securityEnforcer
    ) {
        this.configEngineService = configEngineService;
        this.securityEnforcer = securityEnforcer;
    }

    @GetMapping("/config/snapshot")
    public ResponseEntity<Snapshot> getSnapshot(
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantId
    ) {
        String effectiveTenant = securityEnforcer.resolveEffectiveTenant(tenantId);
        Snapshot snapshot = configEngineService.getCurrentSnapshot(effectiveTenant);
        return ResponseEntity.ok(snapshot);
    }

    @GetMapping("/config/changes")
    public ResponseEntity<DeltaResponse> getChanges(
            @RequestParam(name = "since", defaultValue = "0") long sinceVersion,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantId
    ) {
        String effectiveTenant = securityEnforcer.resolveEffectiveTenant(tenantId);
        DeltaResponse delta = configEngineService.getChangesSince(sinceVersion, effectiveTenant);
        return ResponseEntity.ok(delta);
    }

    @GetMapping("/config/explain")
    public ResponseEntity<ResolutionExplanation> explain(
            @RequestParam("key") String key,
            @RequestParam(name = "client", required = false) String client,
            @RequestParam(name = "office", required = false) String office,
            @RequestParam(name = "shift", required = false) String shift,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantId
    ) {
        String requestedTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId : client;
        String effectiveTenant = securityEnforcer.resolveEffectiveTenant(requestedTenant);
        Scope scope = new Scope(effectiveTenant, office, shift);
        ResolutionExplanation explanation = configEngineService.explain(key, scope, effectiveTenant);
        return ResponseEntity.ok(explanation);
    }

    @GetMapping("/diff")
    public ResponseEntity<ConfigDelta> getDiff(
            @RequestParam("from") long fromVersion,
            @RequestParam("to") long toVersion,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantId
    ) {
        String effectiveTenant = securityEnforcer.resolveEffectiveTenant(tenantId);
        ConfigDelta diff = configEngineService.getDiff(fromVersion, toVersion, effectiveTenant);
        return ResponseEntity.ok(diff);
    }
}
