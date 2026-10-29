package com.moveinsync.config.core.model;

/**
 * Supported data types for configuration settings.
 *
 * Each type has associated parsing and validation logic in {@link com.moveinsync.config.core.engine.ValueValidator}.
 */
public enum SettingType {
    NUMBER,
    DURATION,
    TIME_WINDOW,
    ENUM,
    LIST
}
