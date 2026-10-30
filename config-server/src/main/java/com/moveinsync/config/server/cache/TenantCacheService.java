package com.moveinsync.config.server.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.moveinsync.config.core.model.ConfigDelta;
import com.moveinsync.config.core.model.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * Server-side Caffeine cache for filtered per-tenant snapshots and deltas.
 *
 * <p><strong>Staleness Bound:</strong>
 * Snapshots and deltas are strictly immutable and keyed by exact version numbers {@code (tenantId, version)}.
 * Consequently, any cached entry for version {@code V} is valid forever and has a <strong>staleness bound of 0 ms</strong>.
 * When a new configuration version {@code V+1} is published, subsequent queries request version {@code V+1},
 * computing and caching the new view on first access without serving stale data.
 *
 * <p><strong>Memory & Storm Protection:</strong>
 * Bounded to 5,000 entries with a 30-minute write expiration. During a 500-client reconnect storm,
 * the first client triggers filtering and populates the cache; the remaining 499 clients receive
 * the cached reference in sub-microsecond time with zero repeated CPU allocations.
 */
@Service
public class TenantCacheService {

    private static final Logger log = LoggerFactory.getLogger(TenantCacheService.class);
    private static final int MAX_CACHE_ENTRIES = 5_000;
    private static final Duration EXPIRE_DURATION = Duration.ofMinutes(30);

    public record TenantSnapshotKey(String tenantId, long version) {
        public TenantSnapshotKey {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
        }
    }

    public record TenantDeltaKey(String tenantId, long fromVersion, long toVersion) {
        public TenantDeltaKey {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
        }
    }

    private final Cache<TenantSnapshotKey, Snapshot> snapshotCache;
    private final Cache<TenantDeltaKey, ConfigDelta> deltaCache;

    public TenantCacheService() {
        this.snapshotCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHE_ENTRIES)
                .expireAfterWrite(EXPIRE_DURATION)
                .recordStats()
                .build();

        this.deltaCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHE_ENTRIES)
                .expireAfterWrite(EXPIRE_DURATION)
                .recordStats()
                .build();
    }

    /**
     * Retrieves or computes a tenant-filtered Snapshot for the specified version.
     *
     * @param tenantId target tenant ID
     * @param version master version
     * @param computer computation function invoked on cache miss
     * @return tenant-isolated Snapshot
     */
    public Snapshot getSnapshot(String tenantId, long version, Function<String, Snapshot> computer) {
        if (tenantId == null || tenantId.isBlank()) {
            return computer.apply(null);
        }
        TenantSnapshotKey key = new TenantSnapshotKey(tenantId, version);
        return snapshotCache.get(key, k -> computer.apply(k.tenantId()));
    }

    /**
     * Retrieves or computes a tenant-filtered ConfigDelta between two versions.
     *
     * @param tenantId target tenant ID
     * @param fromVersion starting version
     * @param toVersion target version
     * @param computer computation function invoked on cache miss
     * @return tenant-isolated ConfigDelta
     */
    public ConfigDelta getDelta(String tenantId, long fromVersion, long toVersion, Function<String, ConfigDelta> computer) {
        if (tenantId == null || tenantId.isBlank()) {
            return computer.apply(null);
        }
        TenantDeltaKey key = new TenantDeltaKey(tenantId, fromVersion, toVersion);
        return deltaCache.get(key, k -> computer.apply(k.tenantId()));
    }

    /**
     * Invalidate all cached entries.
     */
    public void invalidateAll() {
        snapshotCache.invalidateAll();
        deltaCache.invalidateAll();
        log.debug("Tenant snapshot and delta Caffeine caches invalidated");
    }

    public CacheStats getSnapshotCacheStats() {
        return snapshotCache.stats();
    }

    public CacheStats getDeltaCacheStats() {
        return deltaCache.stats();
    }
}
