package com.moveinsync.config.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.client.dto.ClientDeltaResponse;
import com.moveinsync.config.client.store.ClientJsonMapper;
import com.moveinsync.config.client.store.DiskSnapshotPersistence;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.OverrideValue;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.core.model.ValidationRule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigClientLiveTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = ClientJsonMapper.get();

    private final AtomicLong serverVersion = new AtomicLong(1L);
    private Snapshot currentServerSnapshot;
    private final List<OutputStream> sseSubscribers = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startMockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        // Baseline server snapshot
        SettingDefinition def = new SettingDefinition("cutoff", SettingType.NUMBER, "Cutoff", ValidationRule.empty(), 60.0);
        currentServerSnapshot = new Snapshot(1L, Map.of("cutoff", def), Map.of());

        // Snapshot endpoint: GET /api/config/snapshot
        server.createContext("/api/config/snapshot", exchange -> {
            byte[] response = mapper.writeValueAsBytes(currentServerSnapshot);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        // Changes endpoint: GET /api/config/changes
        server.createContext("/api/config/changes", exchange -> {
            long currentVer = serverVersion.get();
            OverrideValue ov = new OverrideValue("cutoff", Scope.client("clientA"), Level.CLIENT, 30.0);
            ClientDeltaResponse delta = new ClientDeltaResponse(
                    1L,
                    currentVer,
                    currentVer,
                    Collections.emptyList(),
                    List.of(ov),
                    Collections.emptyList()
            );
            byte[] response = mapper.writeValueAsBytes(delta);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        // SSE endpoint: GET /api/config/stream
        server.createContext("/api/config/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0); // chunked
            OutputStream os = exchange.getResponseBody();
            sseSubscribers.add(os);
            // Send initial hint
            String initEvent = "data: {\"latestVersion\": " + serverVersion.get() + "}\n\n";
            os.write(initEvent.getBytes(StandardCharsets.UTF_8));
            os.flush();
        });

        server.start();
    }

    @AfterEach
    void stopMockServer() {
        for (OutputStream os : sseSubscribers) {
            try {
                os.close();
            } catch (IOException ignored) {}
        }
        sseSubscribers.clear();
        server.stop(0);
    }

    @Test
    @DisplayName("Offline Startup: Client boots up from disk snapshot when server is offline")
    void offlineStartupWithDisk() {
        // Pre-populate disk snapshot at version 99
        Path diskPath = tempDir.resolve("offline-snapshot.json");
        DiskSnapshotPersistence persistence = new DiskSnapshotPersistence(diskPath);
        SettingDefinition def = new SettingDefinition("offline.key", SettingType.NUMBER, "desc", ValidationRule.empty(), 99.0);
        Snapshot diskSnap = new Snapshot(99L, Map.of(def.key(), def), Map.of());
        persistence.save(diskSnap);

        // Point client to an unreachable port
        ClientConfig config = ClientConfig.builder()
                .serverUrl("http://localhost:59999")
                .diskSnapshotPath(diskPath)
                .startBackgroundSync(false)
                .build();

        try (ConfigClient client = ConfigClient.create(config)) {
            assertThat(client.getCurrentVersion()).isEqualTo(99L);
            ConfigView view = client.view();
            assertThat(view.getDouble("offline.key")).isEqualTo(99.0);
        }
    }

    @Test
    @DisplayName("Initial Sync & Delta Live Propagation: Client receives SSE push and updates in-memory snapshot")
    void liveSyncAndPropagation() throws Exception {
        Path diskPath = tempDir.resolve("client-live.json");

        ClientConfig config = ClientConfig.builder()
                .serverUrl("http://localhost:" + port)
                .tenantId("clientA")
                .diskSnapshotPath(diskPath)
                .pollInterval(Duration.ofSeconds(10))
                .startBackgroundSync(true)
                .build();

        try (ConfigClient client = ConfigClient.create(config)) {
            // Wait for initial sync
            Thread.sleep(300);
            assertThat(client.getCurrentVersion()).isEqualTo(1L);
            assertThat(client.view(Scope.client("clientA")).getDouble("cutoff")).isEqualTo(60.0);

            // Server publishes new version 2
            serverVersion.set(2L);
            String pushHint = "data: {\"latestVersion\": 2}\n\n";
            for (OutputStream os : sseSubscribers) {
                try {
                    os.write(pushHint.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException ignored) {}
            }

            // Client should pull delta within seconds and advance to version 2
            long deadline = System.currentTimeMillis() + 3000;
            while (client.getCurrentVersion() < 2L && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assertThat(client.getCurrentVersion()).isEqualTo(2L);
            ConfigView updatedView = client.view(Scope.client("clientA"));
            assertThat(updatedView.getDouble("cutoff")).isEqualTo(30.0);

            // Verify disk snapshot was updated
            DiskSnapshotPersistence checkDisk = new DiskSnapshotPersistence(diskPath);
            assertThat(checkDisk.load()).isPresent();
            assertThat(checkDisk.load().get().version()).isEqualTo(2L);
        }
    }
}
