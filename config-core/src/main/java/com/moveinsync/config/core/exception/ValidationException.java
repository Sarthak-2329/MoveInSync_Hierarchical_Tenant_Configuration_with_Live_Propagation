package com.moveinsync.config.core.exception;

/**
 * Thrown when a setting value, override, or change set violates validation constraints.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
