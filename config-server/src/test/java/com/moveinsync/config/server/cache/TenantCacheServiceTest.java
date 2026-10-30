package com.moveinsync.config.server.cache;

import com.moveinsync.config.core.model.ConfigDelta;
import com.moveinsync.config.core.model.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class TenantCacheServiceTest {

    @Test
    @DisplayName("TenantCacheService caches filtered snapshot and avoids repeated computation")
    void cachesSnapshotPerTenantAndVersion() {
        TenantCacheService cacheService = new TenantCacheService();
        AtomicInteger computeCount = new AtomicInteger(0);

        Snapshot mockSnapshot = Snapshot.empty();

        // 1. First call triggers computation
        Snapshot snap1 = cacheService.getSnapshot("clientA", 1L, tid -> {
            computeCount.incrementAndGet();
            return mockSnapshot;
        });
        assertThat(snap1).isSameAs(mockSnapshot);
        assertThat(computeCount.get()).isEqualTo(1);

        // 2. Second call with same tenant and version must hit cache (no computation)
        Snapshot snap2 = cacheService.getSnapshot("clientA", 1L, tid -> {
            computeCount.incrementAndGet();
            return Snapshot.empty();
        });
        assertThat(snap2).isSameAs(mockSnapshot);
        assertThat(computeCount.get()).isEqualTo(1); // Still 1

        // 3. Different version triggers new computation
        cacheService.getSnapshot("clientA", 2L, tid -> {
            computeCount.incrementAndGet();
            return Snapshot.empty();
        });
        assertThat(computeCount.get()).isEqualTo(2);

        // 4. Different tenant triggers new computation
        cacheService.getSnapshot("clientB", 1L, tid -> {
            computeCount.incrementAndGet();
            return Snapshot.empty();
        });
        assertThat(computeCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("TenantCacheService caches delta and avoids repeated diff computation")
    void cachesDelta() {
        TenantCacheService cacheService = new TenantCacheService();
        AtomicInteger deltaComputeCount = new AtomicInteger(0);
        ConfigDelta mockDelta = ConfigDelta.empty(2L);

        // First call computes
        ConfigDelta delta1 = cacheService.getDelta("clientA", 1L, 2L, tid -> {
            deltaComputeCount.incrementAndGet();
            return mockDelta;
        });
        assertThat(delta1).isSameAs(mockDelta);
        assertThat(deltaComputeCount.get()).isEqualTo(1);

        // Second call hits cache
        ConfigDelta delta2 = cacheService.getDelta("clientA", 1L, 2L, tid -> {
            deltaComputeCount.incrementAndGet();
            return ConfigDelta.empty(2L);
        });
        assertThat(delta2).isSameAs(mockDelta);
        assertThat(deltaComputeCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("TenantCacheService invalidateAll clears all entries")
    void invalidatesCache() {
        TenantCacheService cacheService = new TenantCacheService();
        AtomicInteger computeCount = new AtomicInteger(0);

        cacheService.getSnapshot("clientA", 1L, tid -> {
            computeCount.incrementAndGet();
            return Snapshot.empty();
        });
        assertThat(computeCount.get()).isEqualTo(1);

        cacheService.invalidateAll();

        cacheService.getSnapshot("clientA", 1L, tid -> {
            computeCount.incrementAndGet();
            return Snapshot.empty();
        });
        assertThat(computeCount.get()).isEqualTo(2);
    }
}
