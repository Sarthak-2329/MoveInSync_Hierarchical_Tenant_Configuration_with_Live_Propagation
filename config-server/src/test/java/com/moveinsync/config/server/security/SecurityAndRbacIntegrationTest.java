package com.moveinsync.config.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.server.dto.ApplyChangeSetRequest;
import com.moveinsync.config.server.dto.ChangeItemDto;
import com.moveinsync.config.server.dto.CreateDefinitionRequest;
import com.moveinsync.config.server.dto.RollbackRequest;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "security.enabled=true",
        "security.api-keys[0].key=admin-test-key",
        "security.api-keys[0].role=ADMIN",
        "security.api-keys[0].tenantId=*",
        "security.api-keys[1].key=operator-google-key",
        "security.api-keys[1].role=OPERATOR",
        "security.api-keys[1].tenantId=google",
        "security.api-keys[2].key=viewer-google-key",
        "security.api-keys[2].role=VIEWER",
        "security.api-keys[2].tenantId=google"
})
public class SecurityAndRbacIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChangeLogRepository changeLogRepository;

    @Autowired
    private ConfigEngineService configEngineService;

    @BeforeEach
    void setup() {
        changeLogRepository.deleteAll();
        configEngineService.init();

        // Seed initial definition as ADMIN
        CreateDefinitionRequest def = new CreateDefinitionRequest(
                "booking.cutoff.minutes",
                SettingType.NUMBER,
                "Cutoff",
                new ValidationRuleDto(0.0, 180.0, null, null, null, null, null),
                60.0,
                "admin",
                "Init"
        );
        configEngineService.registerDefinition(def.toDomain(), "admin", "Init");
    }

    @Test
    @DisplayName("401 Unauthorized: Missing X-API-KEY header returns standard error response")
    void missingApiKeyReturns401() throws Exception {
        mockMvc.perform(get("/api/config/snapshot"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message", containsString("Missing X-API-KEY")));
    }

    @Test
    @DisplayName("401 Unauthorized: Invalid X-API-KEY header returns standard error response")
    void invalidApiKeyReturns401() throws Exception {
        mockMvc.perform(get("/api/config/snapshot")
                        .header("X-API-KEY", "malicious-fake-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message", containsString("Invalid API key")));
    }

    @Test
    @DisplayName("Bypass: Actuator health and prometheus endpoints are accessible without X-API-KEY")
    void actuatorPublicAccess() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("RBAC: ADMIN role has unrestricted write access across all scopes and tenants")
    void adminCanPerformAllOperations() throws Exception {
        ApplyChangeSetRequest batch = new ApplyChangeSetRequest(
                "admin",
                "Global & multi-tenant updates",
                List.of(
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto(null, null, null), Level.GLOBAL, 50.0, false),
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", null, null), Level.CLIENT, 40.0, false),
                        new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("uber", null, null), Level.CLIENT, 30.0, false)
                )
        );

        mockMvc.perform(post("/api/changesets")
                        .header("X-API-KEY", "admin-test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
    }

    @Test
    @DisplayName("RBAC: OPERATOR can apply change set within their assigned tenant (google)")
    void operatorCanWriteOwnTenant() throws Exception {
        ApplyChangeSetRequest batch = new ApplyChangeSetRequest(
                "google-op",
                "Update google office",
                List.of(new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", "bangalore", null), Level.OFFICE, 35.0, false))
        );

        mockMvc.perform(post("/api/changesets")
                        .header("X-API-KEY", "operator-google-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
    }

    @Test
    @DisplayName("RBAC: OPERATOR is rejected with 403 Forbidden when attempting to modify another tenant (uber)")
    void operatorCannotWriteOtherTenant() throws Exception {
        ApplyChangeSetRequest maliciousBatch = new ApplyChangeSetRequest(
                "google-op",
                "Malicious write to uber",
                List.of(new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("uber", null, null), Level.CLIENT, 15.0, false))
        );

        mockMvc.perform(post("/api/changesets")
                        .header("X-API-KEY", "operator-google-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(maliciousBatch)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message", containsString("Operators can only modify configuration within their assigned tenant 'google'")));
    }

    @Test
    @DisplayName("RBAC: OPERATOR is rejected with 403 Forbidden when attempting to create setting definition")
    void operatorCannotCreateDefinition() throws Exception {
        CreateDefinitionRequest def = new CreateDefinitionRequest(
                "new.setting",
                SettingType.NUMBER,
                "New",
                new ValidationRuleDto(0.0, 100.0, null, null, null, null, null),
                10.0,
                "op",
                "Unauthorized definition"
        );

        mockMvc.perform(post("/api/definitions")
                        .header("X-API-KEY", "operator-google-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(def)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("RBAC: OPERATOR is rejected with 403 Forbidden when attempting rollback")
    void operatorCannotRollback() throws Exception {
        RollbackRequest rollback = new RollbackRequest("op", "Revert");
        mockMvc.perform(post("/api/rollback/0")
                        .header("X-API-KEY", "operator-google-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rollback)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("RBAC: VIEWER (employee/client library) can read own tenant snapshot")
    void viewerCanReadOwnTenant() throws Exception {
        mockMvc.perform(get("/api/config/snapshot")
                        .header("X-API-KEY", "viewer-google-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitions.['booking.cutoff.minutes']").exists());
    }

    @Test
    @DisplayName("RBAC: VIEWER is rejected with 403 Forbidden when attempting to mutate state via POST")
    void viewerCannotMutateState() throws Exception {
        ApplyChangeSetRequest batch = new ApplyChangeSetRequest(
                "viewer",
                "Write attempt",
                List.of(new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", null, null), Level.CLIENT, 25.0, false))
        );

        mockMvc.perform(post("/api/changesets")
                        .header("X-API-KEY", "viewer-google-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message", containsString("Viewers have read-only access")));
    }

    @Test
    @DisplayName("Tenant Isolation: VIEWER attempting to query another tenant receives 403 Forbidden")
    void viewerCannotQueryOtherTenant() throws Exception {
        mockMvc.perform(get("/api/config/snapshot")
                        .header("X-API-KEY", "viewer-google-key")
                        .header("X-Tenant-Id", "uber"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message", containsString("Access denied for tenant 'uber'")));
    }
}
