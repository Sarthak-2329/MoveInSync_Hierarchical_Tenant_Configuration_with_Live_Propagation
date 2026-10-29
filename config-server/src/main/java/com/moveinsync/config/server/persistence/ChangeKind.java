package com.moveinsync.config.server.persistence;

/**
 * Identifies the nature of a committed log entry in the append-only change log.
 */
public enum ChangeKind {
    NORMAL,
    ROLLBACK,
    DEFINITION
}
