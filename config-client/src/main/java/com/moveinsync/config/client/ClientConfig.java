package com.moveinsync.config.client;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Configuration options for initializing a {@link ConfigClient}.
 */
public final class ClientConfig {

    private final String serverUrl;
    private final String apiKey;
    private final String tenantId;
    private final Path diskSnapshotPath;
    private final Duration pollInterval;
    private final Duration maxJitter;
    private final HttpClient httpClient;
    private final boolean startBackgroundSync;

    private ClientConfig(Builder builder) {
        this.serverUrl = Objects.requireNonNull(builder.serverUrl, "serverUrl must not be null");
        this.apiKey = builder.apiKey;
        this.tenantId = builder.tenantId;
        this.diskSnapshotPath = builder.diskSnapshotPath;
        this.pollInterval = builder.pollInterval;
        this.maxJitter = builder.maxJitter;
        this.httpClient = builder.httpClient;
        this.startBackgroundSync = builder.startBackgroundSync;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Path getDiskSnapshotPath() {
        return diskSnapshotPath;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public Duration getMaxJitter() {
        return maxJitter;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public boolean isStartBackgroundSync() {
        return startBackgroundSync;
    }

    public static final class Builder {
        private String serverUrl;
        private String apiKey;
        private String tenantId;
        private Path diskSnapshotPath;
        private Duration pollInterval = Duration.ofSeconds(5);
        private Duration maxJitter = Duration.ofSeconds(1);
        private HttpClient httpClient;
        private boolean startBackgroundSync = true;

        public Builder serverUrl(String serverUrl) {
            this.serverUrl = serverUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder diskSnapshotPath(Path diskSnapshotPath) {
            this.diskSnapshotPath = diskSnapshotPath;
            return this;
        }

        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
            return this;
        }

        public Builder maxJitter(Duration maxJitter) {
            this.maxJitter = Objects.requireNonNull(maxJitter, "maxJitter must not be null");
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder startBackgroundSync(boolean startBackgroundSync) {
            this.startBackgroundSync = startBackgroundSync;
            return this;
        }

        public ClientConfig build() {
            return new ClientConfig(this);
        }
    }
}
