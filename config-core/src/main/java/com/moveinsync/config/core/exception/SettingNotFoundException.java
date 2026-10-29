package com.moveinsync.config.core.exception;

/**
 * Thrown when an operation targets a setting key that does not exist.
 */
public class SettingNotFoundException extends RuntimeException {

    public SettingNotFoundException(String key) {
        super("Configuration setting not found: '" + key + "'");
    }
}
