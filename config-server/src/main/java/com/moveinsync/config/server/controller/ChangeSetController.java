package com.moveinsync.config.server.controller;

import com.moveinsync.config.core.model.ChangeSet;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.server.dto.ApplyChangeSetRequest;
import com.moveinsync.config.server.dto.RollbackRequest;
import com.moveinsync.config.server.dto.RollbackResponse;
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
 */
@RestController
@RequestMapping("/api")
public class ChangeSetController {

    private final ConfigEngineService configEngineService;

    public ChangeSetController(ConfigEngineService configEngineService) {
        this.configEngineService = configEngineService;
    }

    @PostMapping("/changesets")
    public ResponseEntity<Map<String, Object>> applyChangeSet(@RequestBody ApplyChangeSetRequest request) {
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
        String author = (request != null) ? request.author() : "operator";
        String reason = (request != null) ? request.reason() : "Rollback to version " + targetVersion;
        RollbackResponse response = configEngineService.rollbackTo(targetVersion, author, reason);
        return ResponseEntity.ok(response);
    }
}
