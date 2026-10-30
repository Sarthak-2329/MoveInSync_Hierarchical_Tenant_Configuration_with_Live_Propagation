package com.moveinsync.config.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Configuration properties for API-key-based authentication and multi-tenant access control.
 *
 * <p>Loaded from {@code application.yml} under the {@code security} prefix:
 * <pre>
 * security:
 *   enabled: true
 *   api-keys:
 *     - key: "admin-secret-key"
 *       role: ADMIN
 *       tenantId: "*"
 *     - key: "operator-google-key"
 *       role: OPERATOR
 *       tenantId: "google"
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {

    private boolean enabled = true;
    private List<ApiKeyEntry> apiKeys = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<ApiKeyEntry> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<ApiKeyEntry> apiKeys) {
        this.apiKeys = (apiKeys != null) ? apiKeys : new ArrayList<>();
    }

    /**
     * Looks up an API key entry.
     *
     * @param key the plain text API key from the request header
     * @return matching ApiKeyEntry or empty Optional
     */
    public Optional<ApiKeyEntry> findByKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return apiKeys.stream()
                .filter(entry -> entry.key().equals(key))
                .findFirst();
    }
}
