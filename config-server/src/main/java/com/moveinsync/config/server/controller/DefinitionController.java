package com.moveinsync.config.server.controller;

import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.server.dto.CreateDefinitionRequest;
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
 */
@RestController
@RequestMapping("/api/definitions")
public class DefinitionController {

    private final ConfigEngineService configEngineService;

    public DefinitionController(ConfigEngineService configEngineService) {
        this.configEngineService = configEngineService;
    }

    @PostMapping
    public ResponseEntity<SettingDefinition> createDefinition(@RequestBody CreateDefinitionRequest request) {
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
