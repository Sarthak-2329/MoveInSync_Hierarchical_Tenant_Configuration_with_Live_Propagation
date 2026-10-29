package com.moveinsync.config.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.server.dto.ApplyChangeSetRequest;
import com.moveinsync.config.server.dto.ChangeItemDto;
import com.moveinsync.config.server.dto.CreateDefinitionRequest;
import com.moveinsync.config.server.dto.RollbackRequest;
import com.moveinsync.config.server.dto.ScopeDto;
import com.moveinsync.config.server.dto.ValidationRuleDto;
import com.moveinsync.config.server.persistence.ChangeLogRepository;
import com.moveinsync.config.server.service.ConfigEngineService;
import com.moveinsync.config.server.service.SseBroadcaster;
import com.moveinsync.config.server.service.TenantFilterService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConfigServerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChangeLogRepository changeLogRepository;

    @Autowired
    private ConfigEngineService configEngineService;

    @Autowired
    private TenantFilterService tenantFilterService;

    @Autowired
    private SseBroadcaster sseBroadcaster;

    @BeforeEach
    void cleanUp() {
        changeLogRepository.deleteAll();
        configEngineService.init(); // Reset in-memory snapshot to version 0
    }

    @Test
    @DisplayName("Create setting definition via POST /api/definitions")
    void createDefinition() throws Exception {
        CreateDefinitionRequest request = new CreateDefinitionRequest(
                "booking.cutoff.minutes",
                SettingType.NUMBER,
                "Booking cutoff time in minutes",
                new ValidationRuleDto(0.0, 180.0, null, null, null, null, null),
                60.0,
                "admin",
                "Initial schema setup"
        );

        mockMvc.perform(post("/api/definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("booking.cutoff.minutes"))
                .andExpect(jsonPath("$.type").value("NUMBER"))
                .andExpect(jsonPath("$.defaultValue").value(60.0));

        assertThat(configEngineService.getCurrentVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Apply atomic change set via POST /api/changesets")
    void applyChangeSet() throws Exception {
        // Register definition first
        createCutoffDefinition();

        ApplyChangeSetRequest batch = new ApplyChangeSetRequest(
                "operator-1",
                "Set client and office overrides",
                List.of(
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", null, null), Level.CLIENT, 45.0, false),
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", "bangalore", null), Level.OFFICE, 30.0, false)
                )
        );

        mockMvc.perform(post("/api/changesets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("APPLIED"));

        assertThat(configEngineService.getCurrentVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Atomic rejection: Invalid item (-30m duration) rejects whole change set with 400 Bad Request")
    void atomicRejectionOfInvalidChangeSet() throws Exception {
        createCutoffDefinition();

        // One valid item, one invalid negative duration
        ApplyChangeSetRequest invalidBatch = new ApplyChangeSetRequest(
                "operator-1",
                "Try bad value",
                List.of(
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", null, null), Level.CLIENT, 45.0, false),
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", "bangalore", null), Level.OFFICE, -30.0, false)
                )
        );

        mockMvc.perform(post("/api/changesets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidBatch)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"))
                .andExpect(jsonPath("$.message", containsString("less than allowed minimum")));

        // Version must NOT advance; remains 1
        assertThat(configEngineService.getCurrentVersion()).isEqualTo(1L);

        // Verify google client has NO override applied
        Snapshot current = configEngineService.getCurrentSnapshot(null);
        assertThat(current.getOverride("booking.cutoff.minutes", Scope.client("google"))).isNull();
    }

    @Test
    @DisplayName("Tenant Isolation: Tenant A cannot see Tenant B overrides in snapshot or delta")
    void tenantIsolation() throws Exception {
        createCutoffDefinition();

        // Apply overrides for both google and uber
        ApplyChangeSetRequest batch = new ApplyChangeSetRequest(
                "admin",
                "Cross tenant test",
                List.of(
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", null, null), Level.CLIENT, 45.0, false),
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("uber", null, null), Level.CLIENT, 20.0, false)
                )
        );
        configEngineService.applyChangeSet(batch.toDomain());

        // Request snapshot with X-Tenant-Id: google
        mockMvc.perform(get("/api/config/snapshot")
                        .header("X-Tenant-Id", "google"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.overridesByKey.['booking.cutoff.minutes'].['CLIENT/google']").exists())
                .andExpect(jsonPath("$.overridesByKey.['booking.cutoff.minutes'].['CLIENT/uber']").doesNotExist());

        // Request changes since version 1 for google
        mockMvc.perform(get("/api/config/changes?since=1")
                        .header("X-Tenant-Id", "google"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.updatedOverrides", hasSize(1)))
                .andExpect(jsonPath("$.updatedOverrides[0].scope.clientId").value("google"));
    }

    @Test
    @DisplayName("Explain mode returns full hierarchical resolution audit trail")
    void explainResolution() throws Exception {
        createCutoffDefinition();

        ApplyChangeSetRequest batch = new ApplyChangeSetRequest(
                "admin",
                "Setup hierarchy",
                List.of(
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", null, null), Level.CLIENT, 45.0, false),
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", "bangalore", null), Level.OFFICE, 30.0, false)
                )
        );
        configEngineService.applyChangeSet(batch.toDomain());

        mockMvc.perform(get("/api/config/explain")
                        .param("key", "booking.cutoff.minutes")
                        .param("client", "google")
                        .param("office", "bangalore")
                        .param("shift", "morning"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningLevel").value("OFFICE"))
                .andExpect(jsonPath("$.winningValue").value(30.0))
                .andExpect(jsonPath("$.evaluations", hasSize(5)));
    }

    @Test
    @DisplayName("Instant Rollback: Rollback to version N creates new monotonic version N' with state of N and diff")
    void rollbackFlow() throws Exception {
        createCutoffDefinition(); // version 1

        ApplyChangeSetRequest batch1 = new ApplyChangeSetRequest(
                "operator",
                "Change 1",
                List.of(new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", null, null), Level.CLIENT, 45.0, false))
        );
        configEngineService.applyChangeSet(batch1.toDomain()); // version 2

        ApplyChangeSetRequest batch2 = new ApplyChangeSetRequest(
                "operator",
                "Change 2",
                List.of(new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", null, null), Level.CLIENT, 35.0, false))
        );
        configEngineService.applyChangeSet(batch2.toDomain()); // version 3

        assertThat(configEngineService.getCurrentVersion()).isEqualTo(3L);

        // Roll back to version 2
        RollbackRequest rollbackReq = new RollbackRequest("operator-admin", "Revert bad change 2");
        mockMvc.perform(post("/api/rollback/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rollbackReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newVersion").value(4))
                .andExpect(jsonPath("$.targetVersion").value(2))
                .andExpect(jsonPath("$.diff.updatedOverrides[0].value").value(45.0));

        // Version strictly advanced to 4
        assertThat(configEngineService.getCurrentVersion()).isEqualTo(4L);

        // Value is restored to 45.0
        Snapshot snapshot = configEngineService.getCurrentSnapshot(null);
        assertThat(snapshot.getOverride("booking.cutoff.minutes", Scope.client("google")).value()).isEqualTo(45.0);
    }

    @Test
    @DisplayName("Server Crash Recovery / Log Replay Test: A newly started engine reconstructs state perfectly from DB")
    void logReplayCrashRecovery() {
        createCutoffDefinition(); // version 1

        ApplyChangeSetRequest batch = new ApplyChangeSetRequest(
                "operator",
                "Permanent settings",
                List.of(
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", null, null), Level.CLIENT, 45.0, false),
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", "bangalore", null), Level.OFFICE, 30.0, false)
                )
        );
        configEngineService.applyChangeSet(batch.toDomain()); // version 2

        assertThat(configEngineService.getCurrentVersion()).isEqualTo(2L);

        // Simulate complete server restart: create a brand new ConfigEngineService instance using the same H2 repository
        ConfigEngineService restartedService = new ConfigEngineService(
                changeLogRepository,
                tenantFilterService,
                sseBroadcaster,
                objectMapper
        );
        restartedService.init(); // replay log

        // Reconstructed state must match perfectly
        assertThat(restartedService.getCurrentVersion()).isEqualTo(2L);
        Snapshot restoredSnap = restartedService.getCurrentSnapshot(null);
        assertThat(restoredSnap.hasDefinition("booking.cutoff.minutes")).isTrue();
        assertThat(restoredSnap.getOverride("booking.cutoff.minutes", Scope.office("google", "bangalore")).value()).isEqualTo(30.0);
    }

    private void createCutoffDefinition() {
        CreateDefinitionRequest def = new CreateDefinitionRequest(
                "booking.cutoff.minutes",
                SettingType.NUMBER,
                "Cutoff",
                new ValidationRuleDto(0.0, 180.0, null, null, null, null, null),
                60.0,
                "admin",
                "Init"
        );
        configEngineService.registerDefinition(def.toDomain(), def.author(), def.reason());
    }
}
