package com.moveinsync.config.client;

import com.moveinsync.config.core.engine.Resolver;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.core.model.TimeWindow;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Concrete implementation of {@link ConfigView}.
 *
 * Encapsulates an immutable {@link Snapshot} reference and target {@link Scope}.
 * Evaluates reads via {@link Resolver#resolve(Snapshot, String, Scope)}.
 */
public final class ConfigViewImpl implements ConfigView {

    private final Snapshot snapshot;
    private final Scope scope;

    public ConfigViewImpl(Snapshot snapshot, Scope scope) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.scope = (scope != null) ? scope : Scope.global();
    }

    @Override
    public Scope scope() {
        return scope;
    }

    @Override
    public long snapshotVersion() {
        return snapshot.version();
    }

    @Override
    public Object get(String key) {
        return Resolver.resolve(snapshot, key, scope);
    }

    @Override
    public int getInt(String key) {
        Object val = get(key);
        if (val instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(val.toString().trim());
    }

    @Override
    public long getLong(String key) {
        Object val = get(key);
        if (val instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(val.toString().trim());
    }

    @Override
    public double getDouble(String key) {
        Object val = get(key);
        if (val instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(val.toString().trim());
    }

    @Override
    public boolean getBoolean(String key) {
        Object val = get(key);
        if (val instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(val.toString().trim());
    }

    @Override
    public String getString(String key) {
        Object val = get(key);
        return (val != null) ? val.toString() : null;
    }

    @Override
    public Duration getDuration(String key) {
        Object val = get(key);
        if (val instanceof Duration d) {
            return d;
        }
        if (val instanceof Number seconds) {
            return Duration.ofSeconds(seconds.longValue());
        }
        String str = val.toString().trim();
        if (str.startsWith("P") || str.startsWith("p")) {
            return Duration.parse(str);
        }
        if (str.toLowerCase().endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(str.substring(0, str.length() - 1).trim()));
        }
        if (str.toLowerCase().endsWith("h")) {
            return Duration.ofHours(Long.parseLong(str.substring(0, str.length() - 1).trim()));
        }
        if (str.toLowerCase().endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(str.substring(0, str.length() - 1).trim()));
        }
        return Duration.ofMinutes(Long.parseLong(str));
    }

    @Override
    public TimeWindow getTimeWindow(String key) {
        Object val = get(key);
        if (val instanceof TimeWindow tw) {
            return tw;
        }
        return TimeWindow.parse(val.toString().trim());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getList(String key) {
        Object val = get(key);
        if (val instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        if (val instanceof Collection<?> col) {
            return col.stream().map(Object::toString).toList();
        }
        if (val instanceof String str) {
            return List.of(str.split(",")).stream().map(String::trim).toList();
        }
        throw new ClassCastException("Value for '" + key + "' is not a List or CSV: " + val.getClass().getName());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> expectedType) {
        Object val = get(key);
        if (expectedType.isInstance(val)) {
            return expectedType.cast(val);
        }
        throw new ClassCastException("Value for '" + key + "' is " + val.getClass().getName()
                + ", not assignable to " + expectedType.getName());
    }

    @Override
    public boolean hasKey(String key) {
        return snapshot.hasDefinition(key);
    }
}
