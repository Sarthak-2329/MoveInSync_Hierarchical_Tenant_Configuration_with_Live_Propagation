package com.moveinsync.config.client.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.client.dto.ClientDeltaResponse;
import com.moveinsync.config.client.store.ClientJsonMapper;
import com.moveinsync.config.core.model.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Executes HTTP synchronization requests against the Configuration Server.
 *
 * Uses the native {@link java.net.http.HttpClient} with zero external network libraries.
 */
public final class HttpConfigSync {

    private static final Logger log = LoggerFactory.getLogger(HttpConfigSync.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpConfigSync(HttpClient httpClient) {
        this.httpClient = (httpClient != null) ? httpClient : HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        this.objectMapper = ClientJsonMapper.get();
    }

    /**
     * Downloads the full configuration snapshot for the tenant.
     *
     * @param serverUrl base URL of the config server (e.g. http://localhost:8080)
     * @param apiKey API key for authentication
     * @param tenantId tenant identifier
     * @return current Snapshot
     */
    public Snapshot fetchFullSnapshot(String serverUrl, String apiKey, String tenantId) throws IOException, InterruptedException {
        String cleanUrl = serverUrl.replaceAll("/+$", "") + "/api/config/snapshot";
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(cleanUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET();

        if (apiKey != null && !apiKey.isBlank()) {
            reqBuilder.header("X-API-KEY", apiKey);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            reqBuilder.header("X-Tenant-Id", tenantId);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch full snapshot from " + cleanUrl + ": HTTP " + response.statusCode()
                    + " - " + response.body());
        }

        return objectMapper.readValue(response.body(), Snapshot.class);
    }

    /**
     * Fetches incremental changes since a given version.
     *
     * @param serverUrl base URL of the config server
     * @param apiKey API key
     * @param tenantId tenant identifier
     * @param sinceVersion the local version number
     * @return ClientDeltaResponse
     */
    public ClientDeltaResponse fetchDelta(String serverUrl, String apiKey, String tenantId, long sinceVersion)
            throws IOException, InterruptedException {
        String cleanUrl = serverUrl.replaceAll("/+$", "") + "/api/config/changes?since=" + sinceVersion;
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(cleanUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET();

        if (apiKey != null && !apiKey.isBlank()) {
            reqBuilder.header("X-API-KEY", apiKey);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            reqBuilder.header("X-Tenant-Id", tenantId);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch delta from " + cleanUrl + ": HTTP " + response.statusCode()
                    + " - " + response.body());
        }

        return objectMapper.readValue(response.body(), ClientDeltaResponse.class);
    }
}
