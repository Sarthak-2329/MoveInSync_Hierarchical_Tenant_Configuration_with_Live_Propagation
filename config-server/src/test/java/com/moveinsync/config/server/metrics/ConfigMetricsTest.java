package com.moveinsync.config.server.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ConfigMetricsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConfigMetrics configMetrics;

    @Test
    @DisplayName("Prometheus actuator endpoint exposes all custom operational indicators")
    void exposesCustomMetersInPrometheusFormat() throws Exception {
        // Exercise custom metrics
        configMetrics.recordChangeApplied();
        configMetrics.recordChangeRejected();
        configMetrics.recordPollHeal();
        configMetrics.recordAuthFailure();
        configMetrics.recordDeltaRequest();

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("changes_applied")))
                .andExpect(content().string(containsString("changes_rejected")))
                .andExpect(content().string(containsString("sse_connections")))
                .andExpect(content().string(containsString("push_to_visible_lag")))
                .andExpect(content().string(containsString("poll_heals")))
                .andExpect(content().string(containsString("auth_failures")))
                .andExpect(content().string(containsString("delta_requests")));
    }
}
