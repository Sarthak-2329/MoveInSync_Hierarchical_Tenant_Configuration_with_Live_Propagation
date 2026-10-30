package com.moveinsync.config.client.store;

import com.moveinsync.config.core.model.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotStoreTest {

    @Test
    @DisplayName("Monotonic forward-only updates: accepts newer versions, rejects stale or equal versions")
    void monotonicUpdates() {
        SnapshotStore store = new SnapshotStore(new Snapshot(10L, Collections.emptyMap(), Collections.emptyMap()));
        assertThat(store.getVersion()).isEqualTo(10L);

        // Applying newer version 15 succeeds
        boolean updated15 = store.updateIfNewer(new Snapshot(15L, Collections.emptyMap(), Collections.emptyMap()));
        assertThat(updated15).isTrue();
        assertThat(store.getVersion()).isEqualTo(15L);

        // Applying older version 12 is strictly rejected
        boolean updated12 = store.updateIfNewer(new Snapshot(12L, Collections.emptyMap(), Collections.emptyMap()));
        assertThat(updated12).isFalse();
        assertThat(store.getVersion()).isEqualTo(15L);

        // Applying same version 15 is rejected
        boolean updatedSame = store.updateIfNewer(new Snapshot(15L, Collections.emptyMap(), Collections.emptyMap()));
        assertThat(updatedSame).isFalse();
        assertThat(store.getVersion()).isEqualTo(15L);

        // Null is rejected
        assertThat(store.updateIfNewer(null)).isFalse();
    }

    @Test
    @DisplayName("Advance version pointer for tenant isolation gaps")
    void advanceVersionPointer() {
        SnapshotStore store = new SnapshotStore(new Snapshot(5L, Collections.emptyMap(), Collections.emptyMap()));

        boolean advanced = store.advanceVersionIfNewer(10L);
        assertThat(advanced).isTrue();
        assertThat(store.getVersion()).isEqualTo(10L);

        // Advancing to older version is rejected
        boolean advancedBack = store.advanceVersionIfNewer(8L);
        assertThat(advancedBack).isFalse();
        assertThat(store.getVersion()).isEqualTo(10L);
    }
}
