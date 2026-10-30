package com.moveinsync.config.server.controller;

import com.moveinsync.config.core.model.RolloutStatus;
import com.moveinsync.config.core.model.StagedRollout;
import com.moveinsync.config.server.dto.CreateRolloutRequest;
import com.moveinsync.config.server.dto.RolloutActionRequest;
import com.moveinsync.config.server.security.AuthContext;
import com.moveinsync.config.server.security.AuthenticatedUser;
import com.moveinsync.config.server.security.TenantSecurityEnforcer;
import com.moveinsync.config.server.service.ConfigEngineService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for staged configuration rollouts (canary deployments) and promotions.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/rollout - stage a new configuration override to canary offices</li>
 *   <li>GET /api/rollout - list staged rollouts for caller's tenant</li>
 *   <li>GET /api/rollout/{id} - get a specific staged rollout</li>
 *   <li>POST /api/rollout/{id}/promote - promote canary override company-wide</li>
 *   <li>POST /api/rollout/{id}/discard - abort canary override, reverting to baseline</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/rollout")
public class RolloutController {

    private final ConfigEngineService configEngineService;
    private final TenantSecurityEnforcer tenantSecurityEnforcer;

    public RolloutController(ConfigEngineService configEngineService, TenantSecurityEnforcer tenantSecurityEnforcer) {
        this.configEngineService = configEngineService;
        this.tenantSecurityEnforcer = tenantSecurityEnforcer;
    }

    @PostMapping
    public ResponseEntity<StagedRollout> createRollout(
            @RequestBody CreateRolloutRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader
    ) {
        tenantSecurityEnforcer.enforceRolloutWrite(request.targetClientId());

        AuthenticatedUser user = AuthContext.getCurrentUser();
        String author = (user != null) ? user.key() : (request.author() != null ? request.author() : "unknown");

        StagedRollout rollout = configEngineService.createStagedRollout(request, author);
        return ResponseEntity.status(HttpStatus.CREATED).body(rollout);
    }

    @GetMapping
    public ResponseEntity<List<StagedRollout>> getRollouts(
            @RequestParam(value = "tenant", required = false) String requestedTenant,
            @RequestParam(value = "status", required = false) RolloutStatus status,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader
    ) {
        String candidateTenant = (requestedTenant != null) ? requestedTenant : tenantHeader;
        String effectiveTenant = tenantSecurityEnforcer.resolveEffectiveTenant(candidateTenant);

        List<StagedRollout> rollouts = configEngineService.getStagedRollouts(effectiveTenant, status);
        return ResponseEntity.ok(rollouts);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StagedRollout> getRolloutById(
            @PathVariable("id") String rolloutId,
            @RequestParam(value = "tenant", required = false) String requestedTenant,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader
    ) {
        String candidateTenant = (requestedTenant != null) ? requestedTenant : tenantHeader;
        String effectiveTenant = tenantSecurityEnforcer.resolveEffectiveTenant(candidateTenant);

        StagedRollout rollout = configEngineService.getStagedRollout(rolloutId, effectiveTenant);
        return ResponseEntity.ok(rollout);
    }

    @PostMapping("/{id}/promote")
    public ResponseEntity<StagedRollout> promoteRollout(
            @PathVariable("id") String rolloutId,
            @RequestBody(required = false) RolloutActionRequest actionRequest
    ) {
        AuthenticatedUser user = AuthContext.getCurrentUser();
        String userTenant = (user != null && !user.isAdmin()) ? user.tenantId() : null;

        StagedRollout existing = configEngineService.getStagedRollout(rolloutId, userTenant);
        tenantSecurityEnforcer.enforceRolloutWrite(existing.targetClientId());

        String author = (user != null) ? user.key() : (actionRequest != null && actionRequest.author() != null ? actionRequest.author() : "unknown");
        String reason = (actionRequest != null && actionRequest.reason() != null) ? actionRequest.reason() : "Promote staged rollout " + rolloutId;

        StagedRollout promoted = configEngineService.promoteRollout(rolloutId, author, reason);
        return ResponseEntity.ok(promoted);
    }

    @PostMapping("/{id}/discard")
    public ResponseEntity<StagedRollout> discardRollout(
            @PathVariable("id") String rolloutId,
            @RequestBody(required = false) RolloutActionRequest actionRequest
    ) {
        AuthenticatedUser user = AuthContext.getCurrentUser();
        String userTenant = (user != null && !user.isAdmin()) ? user.tenantId() : null;

        StagedRollout existing = configEngineService.getStagedRollout(rolloutId, userTenant);
        tenantSecurityEnforcer.enforceRolloutWrite(existing.targetClientId());

        String author = (user != null) ? user.key() : (actionRequest != null && actionRequest.author() != null ? actionRequest.author() : "unknown");
        String reason = (actionRequest != null && actionRequest.reason() != null) ? actionRequest.reason() : "Discard staged rollout " + rolloutId;

        StagedRollout discarded = configEngineService.discardRollout(rolloutId, author, reason);
        return ResponseEntity.ok(discarded);
    }
}
