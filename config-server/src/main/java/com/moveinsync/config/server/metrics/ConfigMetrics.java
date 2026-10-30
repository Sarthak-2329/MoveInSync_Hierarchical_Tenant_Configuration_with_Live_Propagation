package com.moveinsync.config.server.metrics;

import com.moveinsync.config.server.service.SseBroadcaster;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Custom Micrometer metrics collector for the Config Server.
 *
 * <p>Exposes real-time operational indicators to Prometheus (/actuator/prometheus):
 * <ul>
 *   <li>{@code changes_applied}: Total number of valid change sets successfully committed</li>
 *   <li>{@code changes_rejected}: Total number of change sets rejected (validation or conflict)</li>
 *   <li>{@code sse_connections}: Current count of connected live SSE client subscribers</li>
 *   <li>{@code push_to_visible_lag}: Latency distribution of SSE broadcast delivery</li>
 *   <li>{@code poll_heals}: Count of poll syncs that detected/healed missed version updates</li>
 *   <li>{@code auth_failures}: Count of unauthorized (401/403) access attempts</li>
 *   <li>{@code delta_requests}: Total delta /changes queries processed</li>
 * </ul>
 */
@Component
public class ConfigMetrics {

    private final Counter changesApplied;
    private final Counter changesRejected;
    private final Counter pollHeals;
    private final Counter authFailures;
    private final Counter deltaRequests;
    private final Timer pushToVisibleLag;

    public ConfigMetrics(MeterRegistry registry, SseBroadcaster sseBroadcaster) {
        this.changesApplied = Counter.builder("changes_applied")
                .description("Total successful change sets committed to master state")
                .register(registry);

        this.changesRejected = Counter.builder("changes_rejected")
                .description("Total change sets rejected due to validation failure or conflict")
                .register(registry);

        this.pollHeals = Counter.builder("poll_heals")
                .description("Total sync polls that detected and healed missed change pushes")
                .register(registry);

        this.authFailures = Counter.builder("auth_failures")
                .description("Total authentication and authorization failures")
                .register(registry);

        this.deltaRequests = Counter.builder("delta_requests")
                .description("Total requests for incremental configuration delta")
                .register(registry);

        this.pushToVisibleLag = Timer.builder("push_to_visible_lag")
                .description("Latency distribution of SSE push broadcasts")
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(registry);

        // Register gauge for active SSE subscriber connections
        registry.gauge("sse_connections", sseBroadcaster, SseBroadcaster::getActiveConnectionCount);
    }

    public void recordChangeApplied() {
        changesApplied.increment();
    }

    public void recordChangeRejected() {
        changesRejected.increment();
    }

    public void recordPollHeal() {
        pollHeals.increment();
    }

    public void recordAuthFailure() {
        authFailures.increment();
    }

    public void recordDeltaRequest() {
        deltaRequests.increment();
    }

    public void recordPushLag(Duration duration) {
        pushToVisibleLag.record(duration);
    }
}
