package com.moveinsync.config.server.controller;

import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.server.dto.CreateDefinitionRequest;
import com.moveinsync.config.server.security.TenantSecurityEnforcer;
import com.moveinsync.config.server.service.ConfigEngineService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

/**
 * REST controller for managing setting definitions.
 *
 * <p>Access Control: Creating definitions requires {@code ADMIN} role.
 * Viewing definitions is available to all authenticated callers (definitions are platform-global).
 */
@RestController
@RequestMapping("/api/definitions")
public class DefinitionController {

    private final ConfigEngineService configEngineService;
    private final TenantSecurityEnforcer securityEnforcer;

    public DefinitionController(
            ConfigEngineService configEngineService,
            TenantSecurityEnforcer securityEnforcer
    ) {
        this.configEngineService = configEngineService;
        this.securityEnforcer = securityEnforcer;
    }

    @PostMapping
    public ResponseEntity<SettingDefinition> createDefinition(@RequestBody CreateDefinitionRequest request) {
        securityEnforcer.enforceAdmin();
        SettingDefinition definition = request.toDomain();
        Snapshot next = configEngineService.registerDefinition(definition, request.author(), request.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(next.getDefinition(definition.key()));
    }

    @GetMapping
    public ResponseEntity<Collection<SettingDefinition>> listDefinitions() {
        Snapshot current = configEngineService.getCurrentSnapshot(null);
        return ResponseEntity.ok(current.definitions().values());
    }
}
