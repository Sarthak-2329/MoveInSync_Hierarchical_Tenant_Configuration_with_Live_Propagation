package com.moveinsync.config.server.dto;

import java.time.Instant;

/**
 * Standard sanitized error response returned by @RestControllerAdvice.
 *
 * Guarantees:
 * - No internal Java stack traces are ever exposed.
 * - No personal identifiable information (PII) is included.
 * - Includes a unique traceId for correlation with server-side diagnostic logs.
 */
public record ErrorResponse(
        String code,
        String message,
        Instant timestamp,
        String traceId
) {}
