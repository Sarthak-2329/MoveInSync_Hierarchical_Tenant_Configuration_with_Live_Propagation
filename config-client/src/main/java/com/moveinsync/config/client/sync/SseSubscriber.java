package com.moveinsync.config.client.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.client.store.ClientJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Background subscriber consuming Server-Sent Events (SSE) from {@code /api/config/stream}.
 *
 * <p><strong>Push-Pull Model:</strong>
 * The server SSE stream emits lightweight hints containing only {@code { "latestVersion": N }}.
 * Upon receiving a hint where {@code N > currentVersion}, this subscriber triggers the registered
 * callback to immediately pull the delta.
 *
 * <p><strong>Resilience & Self-Healing:</strong>
 * - If the SSE connection drops, times out, or the server restarts, this subscriber initiates
 *   automatic reconnection using exponential backoff with full jitter.
 * - Reconnection requests pass {@code Last-Event-ID} matching the client's current version.
 * - Thread-safe and cancellable via {@link #close()}.
 */
public final class SseSubscriber implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SseSubscriber.class);

    private final String serverUrl;
    private final String apiKey;
    private final String tenantId;
    private final HttpClient httpClient;
    private final LongSupplier currentVersionSupplier;
    private final LongConsumer onVersionHint;
    private final BackoffManager backoffManager;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    public SseSubscriber(
            String serverUrl,
            String apiKey,
            String tenantId,
            HttpClient httpClient,
            LongSupplier currentVersionSupplier,
            LongConsumer onVersionHint
    ) {
        this.serverUrl = serverUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.tenantId = tenantId;
        this.httpClient = (httpClient != null) ? httpClient : HttpClient.newHttpClient();
        this.currentVersionSupplier = currentVersionSupplier;
        this.onVersionHint = onVersionHint;
        this.backoffManager = new BackoffManager(Duration.ofMillis(300), Duration.ofSeconds(10));
        this.objectMapper = ClientJsonMapper.get();
    }

    /**
     * Starts the SSE listener background daemon thread.
     */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = new Thread(this::listenLoop, "config-sse-subscriber");
            workerThread.setDaemon(true);
            workerThread.start();
            log.info("Started SSE subscriber for {}", serverUrl);
        }
    }

    private void listenLoop() {
        int attempt = 0;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                connectAndConsumeStream();
                // If stream closed cleanly, reset attempt counter
                attempt = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                if (!running.get()) {
                    break;
                }
                attempt++;
                Duration delay = backoffManager.calculateDelay(attempt);
                log.warn("SSE stream disconnected ({}), reconnecting in {} ms (attempt {})",
                        ex.getMessage(), delay.toMillis(), attempt);
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("SSE subscriber loop terminated");
    }

    private void connectAndConsumeStream() throws Exception {
        long myVersion = currentVersionSupplier.getAsLong();
        String streamUrl = serverUrl + "/api/config/stream";

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(streamUrl))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .timeout(Duration.ofMinutes(10))
                .GET();

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-API-KEY", apiKey);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            builder.header("X-Tenant-Id", tenantId);
        }
        if (myVersion > 0) {
            builder.header("Last-Event-ID", String.valueOf(myVersion));
        }

        HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("SSE connection rejected by server: HTTP " + response.statusCode());
        }

        log.info("Connected to SSE stream at {} (currentVersion: {})", streamUrl, myVersion);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            StringBuilder dataBuffer = new StringBuilder();

            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    // Empty line signals end of an event block
                    if (dataBuffer.length() > 0) {
                        processEventData(dataBuffer.toString());
                        dataBuffer.setLength(0);
                    }
                } else if (line.startsWith(":")) {
                    // SSE comment / heartbeat, ignore
                    log.trace("Received SSE heartbeat comment");
                } else if (line.startsWith("data:")) {
                    String dataPart = line.substring(5).trim();
                    dataBuffer.append(dataPart);
                }
            }
        }
    }

    private void processEventData(String rawData) {
        try {
            long newVersion = -1;
            if (rawData.startsWith("{")) {
                JsonNode json = objectMapper.readTree(rawData);
                if (json.has("latestVersion")) {
                    newVersion = json.get("latestVersion").asLong();
                }
            } else {
                newVersion = Long.parseLong(rawData.trim());
            }

            if (newVersion > 0) {
                log.debug("Received SSE version hint: {}", newVersion);
                onVersionHint.accept(newVersion);
            }
        } catch (Exception e) {
            log.warn("Failed to parse SSE event data: '{}' ({})", rawData, e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (running.compareAndSet(true, false)) {
            if (workerThread != null) {
                workerThread.interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
