package com.moveinsync.config.server.proofs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.server.dto.ApplyChangeSetRequest;
import com.moveinsync.config.server.dto.ChangeItemDto;
import com.moveinsync.config.server.dto.CreateDefinitionRequest;
import com.moveinsync.config.server.dto.ScopeDto;
import com.moveinsync.config.server.dto.ValidationRuleDto;
import com.moveinsync.config.server.persistence.ChangeLogRepository;
import com.moveinsync.config.server.service.ConfigEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROOF (g): Multi-Tenant Isolation Security & Boundary Enforcement Test.
 *
 * <p><strong>Context & Assignment Requirement:</strong>
 * "Tenant Isolation: so one client can never read another client’s data."
 * "Prove it: Tenant isolation test that tries to read another tenant's data."
 *
 * <p><strong>Security Invariants:</strong>
 * 1. Global definitions and global defaults are shared platform-wide.
 * 2. Overrides at CLIENT, OFFICE, and SHIFT levels are private to each tenant.
 * 3. When Tenant B queries {@code /snapshot}, {@code /changes}, {@code /diff}, or {@code /explain},
 *    the server strictly filters out Tenant A's private configuration rules.
 * 4. Gaps in version numbers (due to Tenant A mutations) are handled transparently via {@code currentVersion}
 *    without exposing Tenant A's data payloads.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TenantIsolationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChangeLogRepository changeLogRepository;

    @Autowired
    private ConfigEngineService configEngineService;

    @BeforeEach
    void setupData() throws Exception {
        changeLogRepository.deleteAll();
        configEngineService.init();

        // Register setting definition: booking.cutoff.minutes
        CreateDefinitionRequest def = new CreateDefinitionRequest(
                "booking.cutoff.minutes",
                SettingType.NUMBER,
                "Booking cutoff in minutes",
                new ValidationRuleDto(0.0, 180.0, null, null, null, null, null),
                60.0,
                "admin",
                "Schema"
        );
        configEngineService.registerDefinition(def.toDomain(), "admin", "Init");

        // Apply overrides:
        // - Global default: 60.0
        // - Global override: 50.0
        // - Tenant A (google): 45.0 for client, 30.0 for office bangalore
        // - Tenant B (uber): 20.0 for client, 10.0 for office hyderabad
        ApplyChangeSetRequest batch = new ApplyChangeSetRequest(
                "admin",
                "Seed tenant data",
                List.of(
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto(null, null, null), Level.GLOBAL, 50.0, false),
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", null, null), Level.CLIENT, 45.0, false),
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", "bangalore", null), Level.OFFICE, 30.0, false),
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("uber", null, null), Level.CLIENT, 20.0, false),
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("uber", "hyderabad", null), Level.OFFICE, 10.0, false)
                )
        );
        configEngineService.applyChangeSet(batch.toDomain());
    }

    @Test
    @DisplayName("Proof (g.1): Tenant B querying /snapshot receives only GLOBAL + Tenant B overrides; Tenant A data is 0% visible")
    void snapshotTenantIsolation() throws Exception {
        mockMvc.perform(get("/api/config/snapshot")
                        .header("X-Tenant-Id", "uber"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitions.['booking.cutoff.minutes']").exists())
                .andExpect(jsonPath("$.overridesByKey.['booking.cutoff.minutes'].['GLOBAL']").exists())
                .andExpect(jsonPath("$.overridesByKey.['booking.cutoff.minutes'].['CLIENT/uber']").exists())
                .andExpect(jsonPath("$.overridesByKey.['booking.cutoff.minutes'].['OFFICE/uber/hyderabad']").exists())
                // Verify strict absence of Tenant A (google) overrides
                .andExpect(jsonPath("$.overridesByKey.['booking.cutoff.minutes'].['CLIENT/google']").doesNotExist())
                .andExpect(jsonPath("$.overridesByKey.['booking.cutoff.minutes'].['OFFICE/google/bangalore']").doesNotExist());
    }

    @Test
    @DisplayName("Proof (g.2): Tenant B querying /changes receives deltas filtered to its own tenant; Tenant A deltas are stripped")
    void deltaTenantIsolation() throws Exception {
        mockMvc.perform(get("/api/config/changes?since=1")
                        .header("X-Tenant-Id", "uber"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                // Only GLOBAL (50.0) + uber overrides (20.0, 10.0)
                .andExpect(jsonPath("$.updatedOverrides", hasSize(3)))
                .andExpect(jsonPath("$.updatedOverrides[?(@.scope.clientId == 'google')]").doesNotExist());
    }

    @Test
    @DisplayName("Proof (g.3): Tenant B attempting to explain Tenant A coordinates is forced to Tenant B scope")
    void explainTenantIsolation() throws Exception {
        // Tenant B malicious attempt: requests explain for client=google
        mockMvc.perform(get("/api/config/explain")
                        .param("key", "booking.cutoff.minutes")
                        .param("client", "google")
                        .param("office", "bangalore")
                        .header("X-Tenant-Id", "uber"))
                .andExpect(status().isOk())
                // Server overrides effective client to authenticated tenant "uber"!
                .andExpect(jsonPath("$.queryScope.clientId").value("uber"))
                // Winning value resolves using uber's hierarchy (falls back to global override 50.0 because uber has no bangalore office)
                .andExpect(jsonPath("$.winningLevel").value("CLIENT"))
                .andExpect(jsonPath("$.winningValue").value(20.0));
    }

    @Test
    @DisplayName("Proof (g.4): Tenant B querying /diff sees only its own changes between versions")
    void diffTenantIsolation() throws Exception {
        mockMvc.perform(get("/api/diff?from=1&to=2")
                        .header("X-Tenant-Id", "uber"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedOverrides", hasSize(3)))
                .andExpect(jsonPath("$.updatedOverrides[?(@.scope.clientId == 'google')]").doesNotExist());
    }
}
