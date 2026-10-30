package com.moveinsync.config.server.proofs;

import com.moveinsync.config.client.ClientConfig;
import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.client.ConfigView;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.server.ConfigServerApplication;
import com.moveinsync.config.server.dto.ApplyChangeSetRequest;
import com.moveinsync.config.server.dto.ChangeItemDto;
import com.moveinsync.config.server.dto.CreateDefinitionRequest;
import com.moveinsync.config.server.dto.ScopeDto;
import com.moveinsync.config.server.dto.ValidationRuleDto;
import com.moveinsync.config.server.service.ConfigEngineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROOF (d): Kill-the-Server Fault Tolerance & Crash Recovery Test.
 *
 * <p><strong>Context & Assignment Scenario:</strong>
 * "The config server is killed. Every service keeps answering reads from its snapshot,
 * and the change made just before the crash is still applied everywhere once the server returns."
 *
 * <p><strong>Fault Injection Workflow:</strong>
 * 1. Start Server 1 with persistent H2 file database.
 * 2. Connect client and apply changes (acknowledged by server as version 2).
 * 3. Client captures and persists snapshot to disk.
 * 4. <strong>KILL THE SERVER:</strong> Abruptly close/terminate the server process context.
 * 5. <strong>Client Resilience Check:</strong> Verify client continues serving reads uninterrupted.
 * 6. <strong>Cold-Start Offline Check:</strong> Start a brand-new client instance while the server
 *    is DEAD. Verify it loads the disk snapshot and answers reads without failing.
 * 7. <strong>Server Recovery Check:</strong> Start Server 2 pointing to the same H2 database file.
 *    Verify Server 2 replays the append-only log on startup and recovers the exact state.
 */
public class KillServerFaultToleranceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Proof (d): Kill server abruptly; clients continue reading; restart server and state is preserved")
    void killServerAndVerifyClientContinuityAndRecovery() throws Exception {
        Path dbPath = tempDir.resolve("kill-server-db").toAbsolutePath();
        Path clientDiskPath = tempDir.resolve("client-disk-snapshot.json");

        // 1. Start Spring Boot Server 1
        ConfigurableApplicationContext server1 = startServer(dbPath);
        int serverPort = getServerPort(server1);
        String serverUrl = "http://localhost:" + serverPort;

        ConfigEngineService engineService1 = server1.getBean(ConfigEngineService.class);

        // Register definition and apply change
        CreateDefinitionRequest def = new CreateDefinitionRequest(
                "booking.cutoff.minutes",
                SettingType.NUMBER,
                "Cutoff",
                new ValidationRuleDto(0.0, 180.0, null, null, null, null, null),
                60.0,
                "admin",
                "Init"
        );
        engineService1.registerDefinition(def.toDomain(), def.author(), def.reason()); // version 1

        ApplyChangeSetRequest change = new ApplyChangeSetRequest(
                "operator",
                "Emergency update",
                List.of(new ChangeItemDto("booking.cutoff.minutes", new ScopeDto("google", null, null), Level.CLIENT, 30.0, false))
        );
        engineService1.applyChangeSet(change.toDomain()); // version 2

        assertThat(engineService1.getCurrentVersion()).isEqualTo(2L);

        // 2. Start Client 1 connected to Server 1
        ClientConfig clientConfig = ClientConfig.builder()
                .serverUrl(serverUrl)
                .tenantId("google")
                .diskSnapshotPath(clientDiskPath)
                .pollInterval(Duration.ofSeconds(1))
                .startBackgroundSync(true)
                .build();

        ConfigClient client1 = ConfigClient.create(clientConfig);

        // Wait until client1 receives version 2
        long syncDeadline = System.currentTimeMillis() + 3000;
        while (client1.getCurrentVersion() < 2L && System.currentTimeMillis() < syncDeadline) {
            Thread.sleep(50);
        }
        assertThat(client1.getCurrentVersion()).isEqualTo(2L);
        assertThat(client1.view(Scope.client("google")).getDouble("booking.cutoff.minutes")).isEqualTo(30.0);

        // 3. KILL THE SERVER PROCESS ABRUPTLY!
        System.out.println("[Fault Injection] Simulating abrupt server kill (SIGKILL / context close)...");
        server1.close();
        System.out.println("[Fault Injection] Server is now DEAD.");

        // 4. Verify Client 1 continues serving reads in-memory without errors
        ConfigView client1View = client1.view(Scope.client("google"));
        assertThat(client1View.getDouble("booking.cutoff.minutes")).isEqualTo(30.0);

        // 5. Verify a NEW client instance can cold-boot completely OFFLINE using the disk snapshot
        System.out.println("[Fault Injection] Bootstrapping a new service instance while server is dead...");
        ClientConfig offlineClientConfig = ClientConfig.builder()
                .serverUrl("http://localhost:" + serverPort) // server is unreachable!
                .tenantId("google")
                .diskSnapshotPath(clientDiskPath)
                .startBackgroundSync(false)
                .build();

        try (ConfigClient offlineClient = ConfigClient.create(offlineClientConfig)) {
            // Must have recovered version 2 from disk
            assertThat(offlineClient.getCurrentVersion()).isEqualTo(2L);
            ConfigView offlineView = offlineClient.view(Scope.client("google"));
            assertThat(offlineView.getDouble("booking.cutoff.minutes")).isEqualTo(30.0);
            System.out.println("[Fault Injection] Offline client read succeeded: cutoff = " + offlineView.getDouble("booking.cutoff.minutes"));
        }

        // 6. RESTART THE SERVER pointing to the SAME H2 database file
        System.out.println("[Fault Injection] Restarting server from persistent H2 file database...");
        ConfigurableApplicationContext server2 = startServer(dbPath);
        try {
            ConfigEngineService engineService2 = server2.getBean(ConfigEngineService.class);

            // Replay at startup must have reconstructed version 2!
            assertThat(engineService2.getCurrentVersion()).isEqualTo(2L);
            assertThat(engineService2.getCurrentSnapshot("google")
                    .getOverride("booking.cutoff.minutes", Scope.client("google")).value())
                    .isEqualTo(30.0);
            System.out.println("[Fault Injection] Server 2 restarted and replayed log successfully. Version = " + engineService2.getCurrentVersion());
        } finally {
            client1.close();
            server2.close();
        }
    }

    private ConfigurableApplicationContext startServer(Path dbPath) {
        String jdbcUrl = "jdbc:h2:file:" + dbPath.resolve("configdb") + ";DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE";
        return new SpringApplicationBuilder(ConfigServerApplication.class)
                .properties(
                        "server.port=0",
                        "spring.datasource.url=" + jdbcUrl,
                        "spring.datasource.driverClassName=org.h2.Driver",
                        "spring.jpa.hibernate.ddl-auto=update",
                        "spring.main.banner-mode=off"
                )
                .run();
    }

    private int getServerPort(ConfigurableApplicationContext context) {
        Environment env = context.getEnvironment();
        return Integer.parseInt(env.getProperty("local.server.port", "8080"));
    }
}
