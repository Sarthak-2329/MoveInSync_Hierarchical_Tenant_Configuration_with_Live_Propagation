package com.moveinsync.config.client;

import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.Snapshot;

/**
 * Public client interface embedded into client services (e.g. Booking Engine, Billing).
 *
 * <p><strong>Key Design Contracts:</strong>
 * 1. <strong>Microsecond Reads:</strong> {@link #view(Scope)} retrieves an in-memory view with zero network overhead.
 * 2. <strong>Multi-Key Consistency:</strong> Capturing a {@link ConfigView} pins all queries to that exact snapshot.
 * 3. <strong>Automatic Live Propagation:</strong> Receives SSE push notifications and heals missed updates via polling.
 * 4. <strong>Offline Resilience:</strong> Starts up from disk snapshot when server is offline and persists updates atomically.
 */
public interface ConfigClient extends AutoCloseable {

    /**
     * Captures a point-in-time, multi-key consistent configuration view for the given scope coordinates.
     *
     * @param scope hierarchical scope (client, office, shift)
     * @return immutable ConfigView
     */
    ConfigView view(Scope scope);

    /**
     * Captures a point-in-time view with global scope.
     */
    default ConfigView view() {
        return view(Scope.global());
    }

    /**
     * Returns the active local configuration version number.
     */
    long getCurrentVersion();

    /**
     * Returns the current immutable Snapshot instance.
     */
    Snapshot getSnapshot();

    /**
     * Forces an immediate on-demand synchronization cycle with the server.
     *
     * @return true if new changes were pulled and applied, false otherwise
     */
    boolean sync();

    /**
     * Closes the client, terminating SSE subscriptions, scheduled pollers, and background threads.
     */
    @Override
    void close();

    /**
     * Factory method to create and initialize a new ConfigClient with the provided configuration.
     */
    static ConfigClient create(ClientConfig config) {
        return new ConfigClientImpl(config);
    }

    /**
     * Entry point to configure a new client using the builder pattern.
     */
    static ClientConfig.Builder builder() {
        return ClientConfig.builder();
    }
}
