package com.moveinsync.config.core.exception;

/**
 * Thrown when attempting to apply a change with a non-monotonic or conflicting version.
 */
public class VersionConflictException extends RuntimeException {

    public VersionConflictException(long currentVersion, long attemptedVersion) {
        super(String.format("Cannot apply version %d: current snapshot version is %d (versions must strictly increase)",
                attemptedVersion, currentVersion));
    }
}
