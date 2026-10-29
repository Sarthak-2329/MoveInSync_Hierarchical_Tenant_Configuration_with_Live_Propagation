package com.moveinsync.config.server.exception;

import com.moveinsync.config.core.exception.SettingNotFoundException;
import com.moveinsync.config.core.exception.ValidationException;
import com.moveinsync.config.core.exception.VersionConflictException;
import com.moveinsync.config.server.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/**
 * Central exception handler for the REST API.
 *
 * Security & Data Privacy Guarantees:
 * 1. Consistent envelope: All errors return {@link ErrorResponse} with standard code, message, and timestamp.
 * 2. Zero PII: Stack traces, internal database details, and user data are never returned to clients.
 * 3. Traceability: Every error response includes a correlation traceId matching server log entries.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(ValidationException ex) {
        String traceId = generateTraceId();
        log.warn("[{}] Validation error: {}", traceId, ex.getMessage());
        ErrorResponse body = new ErrorResponse("INVALID_VALUE", ex.getMessage(), Instant.now(), traceId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(SettingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SettingNotFoundException ex) {
        String traceId = generateTraceId();
        log.warn("[{}] Setting not found: {}", traceId, ex.getMessage());
        ErrorResponse body = new ErrorResponse("SETTING_NOT_FOUND", ex.getMessage(), Instant.now(), traceId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<ErrorResponse> handleVersionConflict(VersionConflictException ex) {
        String traceId = generateTraceId();
        log.warn("[{}] Version conflict: {}", traceId, ex.getMessage());
        ErrorResponse body = new ErrorResponse("VERSION_CONFLICT", ex.getMessage(), Instant.now(), traceId);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        String traceId = generateTraceId();
        log.warn("[{}] Bad request: {}", traceId, ex.getMessage());
        ErrorResponse body = new ErrorResponse("BAD_REQUEST", ex.getMessage(), Instant.now(), traceId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        String traceId = generateTraceId();
        // Log stack trace internally for operators, but NEVER expose it in HTTP response
        log.error("[{}] Internal server error", traceId, ex);
        ErrorResponse body = new ErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "An unexpected internal error occurred. Reference trace ID: " + traceId,
                Instant.now(),
                traceId
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
