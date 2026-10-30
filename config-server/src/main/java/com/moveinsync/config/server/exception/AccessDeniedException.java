package com.moveinsync.config.server.exception;

/**
 * Thrown when an authenticated caller attempts an action prohibited by their role or tenant boundary.
 */
public class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String message) {
        super(message);
    }
}
