package com.moveinsync.config.client.proofs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.client.ClientConfig;
import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.client.dto.ClientDeltaResponse;
import com.moveinsync.config.client.store.ClientJsonMapper;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.OverrideValue;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.core.model.ValidationRule;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROOF (c): Live Propagation Scale Test to 200 Simulated Clients.
 *
 * <p><strong>Context & Assignment Requirement:</strong>
 * "Bounded Staleness: A published change is visible in every connected client within 5 seconds."
 * "Prove it: Measure the propagation time to 200 simulated clients (p50 / p99 publish-to-visible)."
 *
 * <p><strong>Methodology:</strong>
 * 1. Spin up an HTTP/SSE configuration server.
 * 2. Connect 200 concurrent {@link ConfigClient} instances simultaneously.
 * 3. A change set is published on the server advancing the version from 1 to 2.
 * 4. Record the precise wall-clock timestamp when the server commits and broadcasts the hint.
 * 5. Track the exact millisecond when each individual client swaps its in-memory {@link Snapshot}.
 * 6. Compute p50, p90, and p99 publish-to-visible latency across all 200 clients.
 * 7. Assert that 100% of the clients observe the change well under the 5.0s SLA.
 */
public class PropagationScaleTest {

    private static final int CLIENT_COUNT = 200;

    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = ClientJsonMapper.get();

    private final AtomicLong serverVersion = new AtomicLong(1L);
    private Snapshot currentSnapshot;
    private final List<OutputStream> sseClients = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService sharedExecutor;
    private ExecutorService serverExecutor;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 300);
        port = server.getAddress().getPort();
        serverExecutor = Executors.newFixedThreadPool(32);
        server.setExecutor(serverExecutor);

        SettingDefinition def = new SettingDefinition("booking.cutoff", SettingType.NUMBER, "Cutoff", ValidationRule.empty(), 60.0);
        currentSnapshot = new Snapshot(1L, Map.of("booking.cutoff", def), Map.of());

        // Snapshot endpoint
        server.createContext("/api/config/snapshot", exchange -> {
            byte[] response = mapper.writeValueAsBytes(currentSnapshot);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        // Changes delta endpoint
        server.createContext("/api/config/changes", exchange -> {
            long currentVer = serverVersion.get();
            OverrideValue ov = new OverrideValue("booking.cutoff", Scope.client("clientA"), Level.CLIENT, 30.0);
            ClientDeltaResponse delta = new ClientDeltaResponse(
                    1L,
                    currentVer,
                    currentVer,
                    Collections.emptyList(),
                    (currentVer >= 2) ? List.of(ov) : Collections.emptyList(),
                    Collections.emptyList()
            );
            byte[] response = mapper.writeValueAsBytes(delta);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        // SSE streaming endpoint
        server.createContext("/api/config/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            sseClients.add(os);

            String initialHint = "data: {\"latestVersion\": " + serverVersion.get() + "}\n\n";
            os.write(initialHint.getBytes(StandardCharsets.UTF_8));
            os.flush();
        });

        server.start();
        sharedExecutor = Executors.newScheduledThreadPool(64);
    }

    @AfterEach
    void stopServer() {
        for (OutputStream os : sseClients) {
            try {
                os.close();
            } catch (IOException ignored) {}
        }
        sseClients.clear();
        server.stop(0);
        if (sharedExecutor != null) {
            sharedExecutor.shutdownNow();
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Proof (c): Measure publish-to-visible propagation latency across 200 connected clients")
    void measurePropagationTo200Clients() throws Exception {
        List<ConfigClient> clients = new ArrayList<>(CLIENT_COUNT);
        long[] visibleLagsMs = new long[CLIENT_COUNT];
        CountDownLatch allConnected = new CountDownLatch(CLIENT_COUNT);

        // 1. Initialize and connect 200 clients
        for (int i = 0; i < CLIENT_COUNT; i++) {
            ClientConfig config = ClientConfig.builder()
                    .serverUrl("http://localhost:" + port)
                    .tenantId("clientA")
                    .scheduledExecutor(sharedExecutor)
                    .pollInterval(Duration.ofSeconds(10))
                    .maxJitter(Duration.ofMillis(500))
                    .startBackgroundSync(true)
                    .build();

            ConfigClient client = ConfigClient.create(config);
            clients.add(client);
        }

        // Wait until all 200 clients have completed initial sync to version 1 and connected to SSE
        long connectionDeadline = System.currentTimeMillis() + 10_000;
        for (ConfigClient client : clients) {
            while (client.getCurrentVersion() < 1L && System.currentTimeMillis() < connectionDeadline) {
                Thread.sleep(20);
            }
            if (client.getCurrentVersion() >= 1L) {
                allConnected.countDown();
            }
        }
        assertThat(allConnected.await(10, TimeUnit.SECONDS)).isTrue();

        while (sseClients.size() < CLIENT_COUNT && System.currentTimeMillis() < connectionDeadline) {
            Thread.sleep(20);
        }
        assertThat(sseClients.size()).as("All 200 clients should have active SSE subscriptions").isGreaterThanOrEqualTo(CLIENT_COUNT);

        // 2. Publish new configuration version 2 on server
        long publishTimeMs = System.currentTimeMillis();
        serverVersion.set(2L);
        String pushHint = "data: {\"latestVersion\": 2}\n\n";

        // Broadcast hint to all 200 SSE connections
        byte[] hintBytes = pushHint.getBytes(StandardCharsets.UTF_8);
        for (OutputStream os : sseClients) {
            try {
                os.write(hintBytes);
                os.flush();
            } catch (IOException ignored) {}
        }

        // 3. Monitor propagation across each of the 200 clients
        long deadline = System.currentTimeMillis() + 5_000;
        int updatedCount = 0;

        while (System.currentTimeMillis() < deadline && updatedCount < CLIENT_COUNT) {
            updatedCount = 0;
            long now = System.currentTimeMillis();
            for (int i = 0; i < CLIENT_COUNT; i++) {
                if (clients.get(i).getCurrentVersion() >= 2L) {
                    if (visibleLagsMs[i] == 0) {
                        visibleLagsMs[i] = Math.max(1, now - publishTimeMs);
                    }
                    updatedCount++;
                }
            }
            if (updatedCount < CLIENT_COUNT) {
                Thread.sleep(10);
            }
        }

        boolean completedWithinSla = (updatedCount == CLIENT_COUNT);

        // 4. Calculate percentiles
        Arrays.sort(visibleLagsMs);
        long minLag = visibleLagsMs[0];
        long p50Lag = visibleLagsMs[(int) (CLIENT_COUNT * 0.50)];
        long p90Lag = visibleLagsMs[(int) (CLIENT_COUNT * 0.90)];
        long p99Lag = visibleLagsMs[(int) (CLIENT_COUNT * 0.99)];
        long maxLag = visibleLagsMs[CLIENT_COUNT - 1];

        System.out.println("=================================================================");
        System.out.println("         200 CLIENTS LIVE PROPAGATION BENCHMARK RESULTS          ");
        System.out.println("=================================================================");
        System.out.printf("Connected Clients:       %d%n", CLIENT_COUNT);
        System.out.printf("All Completed in SLA:    %s%n", completedWithinSla);
        System.out.printf("Min Publish-to-Visible:  %d ms%n", minLag);
        System.out.printf("p50 (Median):            %d ms%n", p50Lag);
        System.out.printf("p90:                     %d ms%n", p90Lag);
        System.out.printf("p99:                     %d ms%n", p99Lag);
        System.out.printf("Max Lag:                 %d ms%n", maxLag);
        System.out.println("=================================================================");

        // Cleanup
        for (ConfigClient client : clients) {
            client.close();
        }

        // 5. Verification: p99 must be strictly below 5000 ms (5 seconds)
        assertThat(completedWithinSla).isTrue();
        assertThat(p99Lag).as("p99 propagation lag must be strictly under 5,000 ms").isLessThan(5_000L);
    }
}
