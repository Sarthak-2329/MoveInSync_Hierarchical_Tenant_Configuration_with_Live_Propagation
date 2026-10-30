package com.moveinsync.config.client.proofs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.client.dto.ClientDeltaResponse;
import com.moveinsync.config.client.store.ClientJsonMapper;
import com.moveinsync.config.client.store.SnapshotStore;
import com.moveinsync.config.client.sync.BackoffManager;
import com.moveinsync.config.client.sync.HttpConfigSync;
import com.moveinsync.config.core.engine.ChangeSetApplier;
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
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROOF (e): Reconnect Storm Resilience with 500 Concurrent Clients.
 *
 * <p><strong>Context & Assignment Requirement:</strong>
 * "Reconnect Storm: After a server restart, 500 clients reconnecting at once must not overload it.
 * Use jitter and send only what changed since each client's last version."
 *
 * <p><strong>The Mitigation Strategy:</strong>
 * 1. <strong>Lightweight Deltas:</strong> Instead of 500 clients requesting entire 5MB snapshots,
 *    clients request {@code /api/config/changes?since={myVersion}}. The server transfers only
 *    the small diff (few hundred bytes).
 * 2. <strong>Full Jitter Backoff:</strong> Rather than reconnecting in a synchronized wave at T=0,
 *    each client injects a uniformly random jitter delay across its backoff window.
 *    This flattens the connection surge over a manageable time spectrum.
 * 3. <strong>Verification:</strong> 500 clients reconnect in parallel; 100% successfully recover
 *    to the latest version with zero connection drops or server errors.
 */
public class ReconnectStormTest {

    private static final int STORM_CLIENTS = 500;

    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = ClientJsonMapper.get();
    private ExecutorService serverExecutor;

    private final AtomicInteger requestCounter = new AtomicInteger(0);
    private final AtomicInteger errorCounter = new AtomicInteger(0);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 500);
        port = server.getAddress().getPort();
        serverExecutor = Executors.newFixedThreadPool(64);
        server.setExecutor(serverExecutor);

        // Server delta endpoint
        server.createContext("/api/config/changes", exchange -> {
            requestCounter.incrementAndGet();
            try {
                OverrideValue ov = new OverrideValue("max.ride.time", Scope.client("clientA"), Level.CLIENT, 120.0);
                ClientDeltaResponse delta = new ClientDeltaResponse(
                        10L,
                        12L,
                        12L,
                        Collections.emptyList(),
                        List.of(ov),
                        Collections.emptyList()
                );
                byte[] bytes = mapper.writeValueAsBytes(delta);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception ex) {
                errorCounter.incrementAndGet();
                exchange.sendResponseHeaders(500, 0);
            }
        });

        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Proof (e): 500 clients reconnecting simultaneously with full jitter successfully pull deltas without server failure")
    void test500ClientsReconnectStorm() throws Exception {
        SettingDefinition def = new SettingDefinition("max.ride.time", SettingType.NUMBER, "desc", ValidationRule.empty(), 90.0);
        Snapshot baselineV10 = new Snapshot(10L, Map.of(def.key(), def), Map.of());

        ExecutorService threadPool = Executors.newFixedThreadPool(64);
        // Do not share the thread pool with HttpClient to prevent worker starvation deadlock
        HttpClient sharedHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpConfigSync sync = new HttpConfigSync(sharedHttpClient);
        BackoffManager backoff = new BackoffManager(Duration.ofMillis(10), Duration.ofMillis(400));

        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch finishSignal = new CountDownLatch(STORM_CLIENTS);

        List<SnapshotStore> clientStores = new ArrayList<>(STORM_CLIENTS);
        for (int i = 0; i < STORM_CLIENTS; i++) {
            clientStores.add(new SnapshotStore(baselineV10));
        }

        String serverUrl = "http://localhost:" + port;

        // Launch 500 reconnecting tasks
        for (int i = 0; i < STORM_CLIENTS; i++) {
            final int index = i;
            final SnapshotStore store = clientStores.get(i);

            threadPool.submit(() -> {
                try {
                    startSignal.await(); // Synchronize sudden restart trigger

                    // 1. Apply Full Jitter before hitting server
                    backoff.backoff(1);

                    // 2. Fetch delta since version 10
                    ClientDeltaResponse delta = sync.fetchDelta(serverUrl, "key", "clientA", store.getVersion());

                    // 3. Apply delta and swap in-memory store
                    Snapshot updated = ChangeSetApplier.applyDelta(store.get(), delta.toDomainDelta());
                    store.updateIfNewer(updated);
                } catch (Exception ex) {
                    errorCounter.incrementAndGet();
                } finally {
                    finishSignal.countDown();
                }
            });
        }

        long stormStartTime = System.currentTimeMillis();
        // Unleash the storm!
        startSignal.countDown();

        boolean allFinished = finishSignal.await(15, TimeUnit.SECONDS);
        long stormDurationMs = System.currentTimeMillis() - stormStartTime;

        threadPool.shutdownNow();

        System.out.println("=================================================================");
        System.out.println("              RECONNECT STORM TEST RESULTS (500 CLIENTS)         ");
        System.out.println("=================================================================");
        System.out.printf("Total Clients Reconnected:   %d%n", STORM_CLIENTS);
        System.out.printf("Total Server Requests:       %d%n", requestCounter.get());
        System.out.printf("Total Server Errors:         %d%n", errorCounter.get());
        System.out.printf("Storm Total Duration:        %d ms%n", stormDurationMs);
        System.out.println("=================================================================");

        // Assertions
        assertThat(allFinished).isTrue();
        assertThat(errorCounter.get()).as("Server should process all requests without errors").isZero();
        assertThat(requestCounter.get()).isEqualTo(STORM_CLIENTS);

        // Verify all 500 clients successfully advanced to version 12
        for (int i = 0; i < STORM_CLIENTS; i++) {
            assertThat(clientStores.get(i).getVersion()).isEqualTo(12L);
        }
    }
}
