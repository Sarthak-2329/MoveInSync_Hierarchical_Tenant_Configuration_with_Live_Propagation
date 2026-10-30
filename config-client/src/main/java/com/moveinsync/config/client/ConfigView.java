package com.moveinsync.config.client;

import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.TimeWindow;

import java.time.Duration;
import java.util.List;

/**
 * An immutable, point-in-time configuration view bound to a specific {@link Scope}.
 *
 * <p><strong>Multi-Key Consistency Guarantee:</strong>
 * All calls to methods on an instance of {@code ConfigView} query the exact same
 * underlying immutable {@code Snapshot}. Even if an update arrives and the client
 * atomically advances its snapshot version in the background, this view remains anchored
 * to the snapshot captured when {@code client.view(scope)} was called.
 * A request reading multiple correlated settings (e.g., night-window start and end)
 * is mathematically guaranteed never to observe a torn or mixed state.
 *
 * <p><strong>Microsecond Latency:</strong>
 * Reads execute purely in-memory with direct hash-table lookups. Zero locks, zero synchronization,
 * and zero network I/O.
 */
public interface ConfigView {

    /**
     * The scope coordinates used for hierarchical resolution by this view.
     */
    Scope scope();

    /**
     * The exact immutable snapshot version this view is reading from.
     */
    long snapshotVersion();

    /**
     * Resolves the effective setting value for the given key.
     *
     * @param key the configuration setting key
     * @return the resolved setting value
     * @throws com.moveinsync.config.core.exception.SettingNotFoundException if key is undefined
     */
    Object get(String key);

    /**
     * Resolves the setting value as an integer.
     */
    int getInt(String key);

    /**
     * Resolves the setting value as a long.
     */
    long getLong(String key);

    /**
     * Resolves the setting value as a double.
     */
    double getDouble(String key);

    /**
     * Resolves the setting value as a boolean.
     */
    boolean getBoolean(String key);

    /**
     * Resolves the setting value as a String.
     */
    String getString(String key);

    /**
     * Resolves the setting value as a Duration.
     */
    Duration getDuration(String key);

    /**
     * Resolves the setting value as a TimeWindow.
     */
    TimeWindow getTimeWindow(String key);

    /**
     * Resolves the setting value as a List of Strings.
     */
    List<String> getList(String key);

    /**
     * Resolves the setting value typed to the requested class.
     */
    <T> T get(String key, Class<T> expectedType);

    /**
     * Checks if a setting definition exists for the given key.
     */
    boolean hasKey(String key);
}
