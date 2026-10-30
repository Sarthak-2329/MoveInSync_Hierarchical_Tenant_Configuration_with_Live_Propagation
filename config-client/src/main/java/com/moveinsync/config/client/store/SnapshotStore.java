package com.moveinsync.config.client.store;

import com.moveinsync.config.core.model.Snapshot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe in-memory store for the client's current configuration snapshot.
 *
 * <p><strong>Concurrency & Monotonicity Model:</strong>
 * - Reads invoke {@code get()} which returns the active {@link Snapshot} via a single volatile read.
 *   This operation takes single-digit nanoseconds and never blocks reader threads.
 * - Updates use Compare-And-Swap (CAS) to replace the reference atomically.
 * - Forward-only guarantee: Any incoming snapshot with {@code version <= current.version()} is discarded.
 *   This ensures that delayed or reordered network responses can never rewind the client's clock.
 */
public final class SnapshotStore {

    private final AtomicReference<Snapshot> snapshotRef;

    public SnapshotStore() {
        this(Snapshot.empty());
    }

    public SnapshotStore(Snapshot initial) {
        this.snapshotRef = new AtomicReference<>(Objects.requireNonNull(initial, "initial snapshot must not be null"));
    }

    /**
     * Atomically swaps the active snapshot if and only if {@code newSnapshot.version() > current.version()}.
     *
     * @param newSnapshot the new immutable snapshot
     * @return true if the snapshot was updated, false if rejected due to being stale or duplicate
     */
    public boolean updateIfNewer(Snapshot newSnapshot) {
        if (newSnapshot == null) {
            return false;
        }
        while (true) {
            Snapshot current = snapshotRef.get();
            if (newSnapshot.version() <= current.version()) {
                // Stale or duplicate delivery - strictly reject
                return false;
            }
            if (snapshotRef.compareAndSet(current, newSnapshot)) {
                return true;
            }
        }
    }

    /**
     * Advances the local snapshot's version pointer without altering definitions or overrides.
     * Used when a tenant receives an empty delta from a server whose global version has moved forward.
     *
     * @param newVersion the global server version
     * @return true if advanced, false if already at or ahead of newVersion
     */
    public boolean advanceVersionIfNewer(long newVersion) {
        while (true) {
            Snapshot current = snapshotRef.get();
            if (newVersion <= current.version()) {
                return false;
            }
            Snapshot advanced = new Snapshot(newVersion, current.definitions(), current.overridesByKey());
            if (snapshotRef.compareAndSet(current, advanced)) {
                return true;
            }
        }
    }

    /**
     * Retrieves the current immutable snapshot.
     */
    public Snapshot get() {
        return snapshotRef.get();
    }

    /**
     * Returns the current version number.
     */
    public long getVersion() {
        return snapshotRef.get().version();
    }
}
