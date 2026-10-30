package com.moveinsync.config.server.controller;

import com.moveinsync.config.core.model.ChangeSet;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.server.dto.ApplyChangeSetRequest;
import com.moveinsync.config.server.dto.RollbackRequest;
import com.moveinsync.config.server.dto.RollbackResponse;
import com.moveinsync.config.server.security.TenantSecurityEnforcer;
import com.moveinsync.config.server.service.ConfigEngineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for applying atomic change sets and executing instant rollbacks.
 *
 * <p>Access Control:
 * <ul>
 *   <li>Applying change sets: {@code ADMIN} (any scope) or {@code OPERATOR} (own tenant items only).
 *       {@code VIEWER} is forbidden.</li>
 *   <li>Rollbacks: strictly {@code ADMIN} only.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ChangeSetController {

    private final ConfigEngineService configEngineService;
    private final TenantSecurityEnforcer securityEnforcer;

    public ChangeSetController(
            ConfigEngineService configEngineService,
            TenantSecurityEnforcer securityEnforcer
    ) {
        this.configEngineService = configEngineService;
        this.securityEnforcer = securityEnforcer;
    }

    @PostMapping("/changesets")
    public ResponseEntity<Map<String, Object>> applyChangeSet(@RequestBody ApplyChangeSetRequest request) {
        securityEnforcer.enforceChangeSetWrite(request);
        ChangeSet changeSet = request.toDomain();
        Snapshot next = configEngineService.applyChangeSet(changeSet);
        return ResponseEntity.ok(Map.of(
                "version", next.version(),
                "status", "APPLIED",
                "changeSetId", changeSet.id()
        ));
    }

    @PostMapping("/rollback/{version}")
    public ResponseEntity<RollbackResponse> rollback(
            @PathVariable("version") long targetVersion,
            @RequestBody(required = false) RollbackRequest request
    ) {
        securityEnforcer.enforceAdmin();
        String author = (request != null) ? request.author() : "operator";
        String reason = (request != null) ? request.reason() : "Rollback to version " + targetVersion;
        RollbackResponse response = configEngineService.rollbackTo(targetVersion, author, reason);
        return ResponseEntity.ok(response);
    }
}
