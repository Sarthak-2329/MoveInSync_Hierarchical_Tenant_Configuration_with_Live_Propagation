package com.moveinsync.config.server.exception;

/**
 * Thrown when an unauthenticated caller attempts to access a protected endpoint.
 */
public class AuthenticationException extends RuntimeException {
    public AuthenticationException(String message) {
        super(message);
    }
}
