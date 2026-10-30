package com.moveinsync.config.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.RolloutStatus;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.core.model.StagedRollout;
import com.moveinsync.config.server.dto.CreateDefinitionRequest;
import com.moveinsync.config.server.dto.CreateRolloutRequest;
import com.moveinsync.config.server.dto.RolloutActionRequest;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = {
        "security.enabled=true",
        "security.api-keys[0].key=admin-secret-key-1",
        "security.api-keys[0].role=ADMIN",
        "security.api-keys[0].tenantId=*",
        "security.api-keys[1].key=operator-google-key",
        "security.api-keys[1].role=OPERATOR",
        "security.api-keys[1].tenantId=google",
        "security.api-keys[2].key=viewer-google-key",
        "security.api-keys[2].role=VIEWER",
        "security.api-keys[2].tenantId=google",
        "security.api-keys[3].key=operator-uber-key",
        "security.api-keys[3].role=OPERATOR",
        "security.api-keys[3].tenantId=uber"
})
class StagedRolloutIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChangeLogRepository changeLogRepository;

    @Autowired
    private ConfigEngineService configEngineService;

    private static final String ADMIN_KEY = "admin-secret-key-1";
    private static final String GOOGLE_OP_KEY = "operator-google-key";
    private static final String UBER_OP_KEY = "operator-uber-key";
    private static final String GOOGLE_VIEW_KEY = "viewer-google-key";

    @BeforeEach
    void cleanUp() {
        changeLogRepository.deleteAll();
        configEngineService.init();
    }

    private void createBooleanDefinition(String key, boolean defaultValue) throws Exception {
        CreateDefinitionRequest defReq = new CreateDefinitionRequest(
                key,
                SettingType.ENUM,
                "Boolean flag: " + key,
                new ValidationRuleDto(null, null, null, null, Set.of("true", "false"), null, null),
                String.valueOf(defaultValue),
                "admin",
                "Create boolean setting " + key
        );
        mockMvc.perform(post("/api/definitions")
                        .header("X-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(defReq)))
                .andExpect(status().isCreated());
    }

    private void createNumberDefinition(String key, double defaultValue) throws Exception {
        CreateDefinitionRequest defReq = new CreateDefinitionRequest(
                key,
                SettingType.NUMBER,
                "Numeric setting: " + key,
                new ValidationRuleDto(0.0, 1000.0, null, null, null, null, null),
                defaultValue,
                "admin",
                "Create numeric setting " + key
        );
        mockMvc.perform(post("/api/definitions")
                        .header("X-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(defReq)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Stage canary rollout to Bangalore office: verify isolation, explain mode, and promotion")
    void stagedRolloutCanaryAndPromotionJourney() throws Exception {
        String key = "escort.required";
        createBooleanDefinition(key, false);

        // 1. Initial resolution: both Bangalore and Hyderabad resolve default "false"
        mockMvc.perform(get("/api/config/snapshot")
                        .header("X-API-Key", ADMIN_KEY)
                        .param("client", "google")
                        .param("office", "bangalore"))
                .andExpect(status().isOk());

        // 2. Stage rollout of "true" to canary office "bangalore" for client "google"
        CreateRolloutRequest rolloutReq = new CreateRolloutRequest(
                "ro-blr-escort",
                key,
                "google",
                Set.of("bangalore"),
                Level.CLIENT,
                "true",
                "sarthak",
                "Testing escort requirement in Bangalore canary office"
        );

        mockMvc.perform(post("/api/rollout")
                        .header("X-API-Key", GOOGLE_OP_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ro-blr-escort"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stagedValue").value("true"));

        // 3. Canary isolation verification:
        // Bangalore resolves "true"
        mockMvc.perform(get("/api/config/explain")
                        .header("X-API-Key", GOOGLE_OP_KEY)
                        .param("key", key)
                        .param("client", "google")
                        .param("office", "bangalore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningValue").value("true"))
                .andExpect(jsonPath("$.winningLevel").value("OFFICE"));

        // Hyderabad resolves "false" (baseline)
        mockMvc.perform(get("/api/config/explain")
                        .header("X-API-Key", GOOGLE_OP_KEY)
                        .param("key", key)
                        .param("client", "google")
                        .param("office", "hyderabad"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningValue").value("false"));

        // 4. Promote rollout company-wide via POST /api/rollout/{id}/promote
        mockMvc.perform(post("/api/rollout/ro-blr-escort/promote")
                        .header("X-API-Key", GOOGLE_OP_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RolloutActionRequest("sarthak", "Canary verified"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ro-blr-escort"))
                .andExpect(jsonPath("$.status").value("PROMOTED"));

        // 5. Post-promotion: ALL google offices (Bangalore, Hyderabad, Pune) now resolve "true"!
        mockMvc.perform(get("/api/config/explain")
                        .header("X-API-Key", GOOGLE_OP_KEY)
                        .param("key", key)
                        .param("client", "google")
                        .param("office", "bangalore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningValue").value("true"))
                .andExpect(jsonPath("$.winningLevel").value("CLIENT"));

        mockMvc.perform(get("/api/config/explain")
                        .header("X-API-Key", GOOGLE_OP_KEY)
                        .param("key", key)
                        .param("client", "google")
                        .param("office", "hyderabad"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningValue").value("true"))
                .andExpect(jsonPath("$.winningLevel").value("CLIENT"));

        // Unrelated tenant (uber) remains at default "false"
        mockMvc.perform(get("/api/config/explain")
                        .header("X-API-Key", UBER_OP_KEY)
                        .param("key", key)
                        .param("client", "uber")
                        .param("office", "bangalore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningValue").value("false"));
    }

    @Test
    @DisplayName("Discarding active staged rollout immediately reverts canary office to baseline")
    void discardStagedRolloutRevertsImmediately() throws Exception {
        String key = "booking.cutoff.minutes";
        createNumberDefinition(key, 30.0);

        // Stage rollout to canary office "hyderabad"
        CreateRolloutRequest rolloutReq = new CreateRolloutRequest(
                "ro-hyd-cutoff",
                key,
                "google",
                Set.of("hyderabad"),
                Level.CLIENT,
                45.0,
                "sarthak",
                "Canary test for 45 min cut-off"
        );

        mockMvc.perform(post("/api/rollout")
                        .header("X-API-Key", GOOGLE_OP_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated());

        // Hyderabad receives 45.0
        mockMvc.perform(get("/api/config/explain")
                        .header("X-API-Key", GOOGLE_OP_KEY)
                        .param("key", key)
                        .param("client", "google")
                        .param("office", "hyderabad"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningValue").value(45.0));

        // Discard rollout
        mockMvc.perform(post("/api/rollout/ro-hyd-cutoff/discard")
                        .header("X-API-Key", GOOGLE_OP_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RolloutActionRequest("sarthak", "Discard canary"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCARDED"));

        // Hyderabad immediately reverts back to default 30.0
        mockMvc.perform(get("/api/config/explain")
                        .header("X-API-Key", GOOGLE_OP_KEY)
                        .param("key", key)
                        .param("client", "google")
                        .param("office", "hyderabad"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningValue").value(30.0));
    }

    @Test
    @DisplayName("Role-based permissions and multi-tenant isolation enforced on staged rollouts")
    void rbacAndTenantIsolationOnRollouts() throws Exception {
        String key = "escort.required";
        createBooleanDefinition(key, false);

        CreateRolloutRequest googleReq = new CreateRolloutRequest(
                "ro-sec-test",
                key,
                "google",
                Set.of("bangalore"),
                Level.CLIENT,
                "true",
                "sarthak",
                "RBAC test"
        );

        // 1. VIEWER cannot create rollout (403)
        mockMvc.perform(post("/api/rollout")
                        .header("X-API-Key", GOOGLE_VIEW_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(googleReq)))
                .andExpect(status().isForbidden());

        // 2. Cross-tenant operator cannot create rollout for another tenant (403)
        mockMvc.perform(post("/api/rollout")
                        .header("X-API-Key", UBER_OP_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(googleReq)))
                .andExpect(status().isForbidden());

        // 3. Google operator creates rollout successfully
        mockMvc.perform(post("/api/rollout")
                        .header("X-API-Key", GOOGLE_OP_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(googleReq)))
                .andExpect(status().isCreated());

        // 4. Uber operator cannot see Google's rollout in listing (tenant isolation)
        mockMvc.perform(get("/api/rollout")
                        .header("X-API-Key", UBER_OP_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 5. Google operator sees their own rollout
        mockMvc.perform(get("/api/rollout")
                        .header("X-API-Key", GOOGLE_OP_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("ro-sec-test"));

        // 6. Uber operator cannot promote Google's rollout (403 or 400 not found)
        mockMvc.perform(post("/api/rollout/ro-sec-test/promote")
                        .header("X-API-Key", UBER_OP_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Replay from H2 change log reconstructs complete staged rollout state on restart")
    void crashRecoveryReconstructsRollouts() throws Exception {
        String key = "booking.cutoff.minutes";
        createNumberDefinition(key, 30.0);

        CreateRolloutRequest rolloutReq = new CreateRolloutRequest(
                "ro-recovery-test",
                key,
                "google",
                Set.of("bangalore"),
                Level.CLIENT,
                45.0,
                "sarthak",
                "Recovery test"
        );

        mockMvc.perform(post("/api/rollout")
                        .header("X-API-Key", GOOGLE_OP_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolloutReq)))
                .andExpect(status().isCreated());

        long versionBeforeCrash = configEngineService.getCurrentVersion();

        // Simulate crash and restart by re-invoking init() replay
        configEngineService.init();

        assertThat(configEngineService.getCurrentVersion()).isEqualTo(versionBeforeCrash);

        // Verify Bangalore still resolves canary 45.0 after crash recovery
        mockMvc.perform(get("/api/config/explain")
                        .header("X-API-Key", GOOGLE_OP_KEY)
                        .param("key", key)
                        .param("client", "google")
                        .param("office", "bangalore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningValue").value(45.0));
    }
}
