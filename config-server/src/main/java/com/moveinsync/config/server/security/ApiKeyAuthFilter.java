package com.moveinsync.config.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.server.dto.ErrorResponse;
import com.moveinsync.config.server.metrics.ConfigMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Servlet filter for API-key authentication and MDC context injection.
 *
 * <p><strong>Key Lifecycle:</strong>
 * <ol>
 *   <li>Extracts {@code X-Trace-Id} or generates a unique correlation ID and sets it in MDC.</li>
 *   <li>Bypasses authentication for public endpoints ({@code /actuator/**}, {@code /error}).</li>
 *   <li>Validates {@code X-API-KEY} against registered keys in {@link SecurityProperties}.</li>
 *   <li>On failure: returns 401 Unauthorized JSON error envelope and increments {@code auth_failures} metric.</li>
 *   <li>On success: binds {@link AuthenticatedUser} to {@link AuthContext} and populates MDC with
 *       {@code tenantId} and {@code role}.</li>
 *   <li>In {@code finally}: cleans up {@link AuthContext} and MDC to prevent thread pool leaks.</li>
 * </ol>
 */
@Component
@Order(1)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    public static final String API_KEY_HEADER = "X-API-KEY";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final SecurityProperties securityProperties;
    private final ConfigMetrics configMetrics;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(
            SecurityProperties securityProperties,
            ConfigMetrics configMetrics,
            ObjectMapper objectMapper
    ) {
        this.securityProperties = securityProperties;
        this.configMetrics = configMetrics;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. Setup distributed correlation ID
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put("traceId", traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        String path = request.getRequestURI();

        // 2. Bypass public monitoring endpoints
        if (isPublicPath(path)) {
            try {
                filterChain.doFilter(request, response);
            } finally {
                cleanupMdcAndAuth();
            }
            return;
        }

        // 3. Check if security is explicitly disabled (e.g. in test suites that mock auth)
        if (!securityProperties.isEnabled()) {
            String tenantHeader = request.getHeader("X-Tenant-Id");
            AuthenticatedUser devUser = new AuthenticatedUser("dev", Role.ADMIN, tenantHeader != null ? tenantHeader : "*");
            AuthContext.setCurrentUser(devUser);
            MDC.put("tenantId", devUser.tenantId());
            MDC.put("role", devUser.role().name());
            try {
                filterChain.doFilter(request, response);
            } finally {
                cleanupMdcAndAuth();
            }
            return;
        }

        // 4. Authenticate incoming request
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[{}] Missing {} header for URI: {}", traceId, API_KEY_HEADER, path);
            sendUnauthorized(response, traceId, "Missing " + API_KEY_HEADER + " authentication header");
            return;
        }

        Optional<ApiKeyEntry> matchingEntry = securityProperties.findByKey(apiKey);
        if (matchingEntry.isEmpty()) {
            log.warn("[{}] Invalid API key provided for URI: {}", traceId, path);
            sendUnauthorized(response, traceId, "Invalid API key");
            return;
        }

        ApiKeyEntry entry = matchingEntry.get();
        AuthenticatedUser user = new AuthenticatedUser(entry.key(), entry.role(), entry.tenantId());
        AuthContext.setCurrentUser(user);

        MDC.put("tenantId", user.tenantId());
        MDC.put("role", user.role().name());

        try {
            filterChain.doFilter(request, response);
        } finally {
            cleanupMdcAndAuth();
        }
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/actuator") || path.startsWith("/error");
    }

    private void sendUnauthorized(HttpServletResponse response, String traceId, String message) throws IOException {
        configMetrics.recordAuthFailure();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ErrorResponse errorBody = new ErrorResponse(
                "UNAUTHORIZED",
                message,
                Instant.now(),
                traceId
        );
        response.getWriter().write(objectMapper.writeValueAsString(errorBody));
    }

    private void cleanupMdcAndAuth() {
        AuthContext.clear();
        MDC.clear();
    }
}
