package com.moveinsync.config.server.exception;

import com.moveinsync.config.core.exception.SettingNotFoundException;
import com.moveinsync.config.core.exception.ValidationException;
import com.moveinsync.config.core.exception.VersionConflictException;
import com.moveinsync.config.server.dto.ErrorResponse;
import com.moveinsync.config.server.metrics.ConfigMetrics;
import com.moveinsync.config.server.util.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/**
 * Central exception handler for the REST API.
 *
 * <p><strong>Security & Data Privacy Guarantees:</strong>
 * <ol>
 *   <li>Consistent JSON Envelope: All errors return {@link ErrorResponse} with code, message, and timestamp.</li>
 *   <li>Zero PII: All messages pass through {@link PiiMasker} to strip phone, email, or location coordinates.</li>
 *   <li>Zero Leakage: Internal stack traces and database implementation details are NEVER returned to clients.</li>
 *   <li>Traceability: Every error response includes a correlation {@code traceId} matching server log entries.</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ConfigMetrics configMetrics;

    public GlobalExceptionHandler(ConfigMetrics configMetrics) {
        this.configMetrics = configMetrics;
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(ValidationException ex) {
        configMetrics.recordChangeRejected();
        String traceId = getOrCreateTraceId();
        String safeMessage = PiiMasker.mask(ex.getMessage());
        log.warn("[{}] Validation error: {}", traceId, safeMessage);
        ErrorResponse body = new ErrorResponse("INVALID_VALUE", safeMessage, Instant.now(), traceId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(SettingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SettingNotFoundException ex) {
        String traceId = getOrCreateTraceId();
        String safeMessage = PiiMasker.mask(ex.getMessage());
        log.warn("[{}] Setting not found: {}", traceId, safeMessage);
        ErrorResponse body = new ErrorResponse("SETTING_NOT_FOUND", safeMessage, Instant.now(), traceId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<ErrorResponse> handleVersionConflict(VersionConflictException ex) {
        String traceId = getOrCreateTraceId();
        String safeMessage = PiiMasker.mask(ex.getMessage());
        log.warn("[{}] Version conflict: {}", traceId, safeMessage);
        ErrorResponse body = new ErrorResponse("VERSION_CONFLICT", safeMessage, Instant.now(), traceId);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        configMetrics.recordAuthFailure();
        String traceId = getOrCreateTraceId();
        String safeMessage = PiiMasker.mask(ex.getMessage());
        log.warn("[{}] Access denied: {}", traceId, safeMessage);
        ErrorResponse body = new ErrorResponse("FORBIDDEN", safeMessage, Instant.now(), traceId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        configMetrics.recordAuthFailure();
        String traceId = getOrCreateTraceId();
        String safeMessage = PiiMasker.mask(ex.getMessage());
        log.warn("[{}] Authentication failure: {}", traceId, safeMessage);
        ErrorResponse body = new ErrorResponse("UNAUTHORIZED", safeMessage, Instant.now(), traceId);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        String traceId = getOrCreateTraceId();
        String safeMessage = PiiMasker.mask(ex.getMessage());
        log.warn("[{}] Bad request: {}", traceId, safeMessage);
        ErrorResponse body = new ErrorResponse("BAD_REQUEST", safeMessage, Instant.now(), traceId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        String traceId = getOrCreateTraceId();
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

    private String getOrCreateTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        return traceId;
    }
}
