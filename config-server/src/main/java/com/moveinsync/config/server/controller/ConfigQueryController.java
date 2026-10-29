package com.moveinsync.config.server.controller;

import com.moveinsync.config.core.model.ConfigDelta;
import com.moveinsync.config.core.model.ResolutionExplanation;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.server.dto.DeltaResponse;
import com.moveinsync.config.server.service.ConfigEngineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for querying snapshots, deltas, diffs, and resolution explanations.
 */
@RestController
@RequestMapping("/api")
public class ConfigQueryController {

    private final ConfigEngineService configEngineService;

    public ConfigQueryController(ConfigEngineService configEngineService) {
        this.configEngineService = configEngineService;
    }

    @GetMapping("/config/snapshot")
    public ResponseEntity<Snapshot> getSnapshot(
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantId
    ) {
        Snapshot snapshot = configEngineService.getCurrentSnapshot(tenantId);
        return ResponseEntity.ok(snapshot);
    }

    @GetMapping("/config/changes")
    public ResponseEntity<DeltaResponse> getChanges(
            @RequestParam(name = "since", defaultValue = "0") long sinceVersion,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantId
    ) {
        DeltaResponse delta = configEngineService.getChangesSince(sinceVersion, tenantId);
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
        // Enforce tenant isolation: if tenantId header is present, it must match the client query param
        String effectiveClient = (tenantId != null && !tenantId.isBlank()) ? tenantId : client;
        Scope scope = new Scope(effectiveClient, office, shift);
        ResolutionExplanation explanation = configEngineService.explain(key, scope, tenantId);
        return ResponseEntity.ok(explanation);
    }

    @GetMapping("/diff")
    public ResponseEntity<ConfigDelta> getDiff(
            @RequestParam("from") long fromVersion,
            @RequestParam("to") long toVersion,
            @RequestHeader(name = "X-Tenant-Id", required = false) String tenantId
    ) {
        ConfigDelta diff = configEngineService.getDiff(fromVersion, toVersion, tenantId);
        return ResponseEntity.ok(diff);
    }
}
