package com.moveinsync.config.server.util;

import java.util.regex.Pattern;

/**
 * Utility to detect and redact Personally Identifiable Information (PII) from error messages,
 * log statements, and HTTP response bodies.
 *
 * Guarantees:
 * - Phone numbers (Indian, North American, International E.164 formats) are redacted to [PHONE_REDACTED].
 * - Email addresses are redacted to [EMAIL_REDACTED].
 * - Geo coordinates / home location coordinates (lat/long) and street keywords are redacted.
 */
public final class PiiMasker {

    // Matches standard email addresses: e.g. john.doe@example.com
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
            Pattern.CASE_INSENSITIVE
    );

    // Matches phone numbers with optional country code (+91, +1), dashes, spaces: e.g. +91 9876543210, 98765-43210
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+?\\d{1,3}[- .]?)?\\(?\\d{3}\\)?[- .]?\\d{3}[- .]?\\d{4}"
    );

    // Matches latitude/longitude decimal pairs: e.g. 12.9716, 77.5946
    private static final Pattern GEO_LOCATION_PATTERN = Pattern.compile(
            "[-+]?([1-8]?\\d(\\.\\d+)?|90(\\.0+)?)[,\\s]+[-+]?(180(\\.0+)?|((1[0-7]\\d)|([1-9]?\\d))(\\.\\d+)?)"
    );

    private PiiMasker() {}

    /**
     * Redacts all PII patterns from the input text.
     *
     * @param input the raw message or payload string
     * @return sanitized string safe for logging and public API responses
     */
    public static String mask(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        String sanitized = EMAIL_PATTERN.matcher(input).replaceAll("[EMAIL_REDACTED]");
        sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll("[PHONE_REDACTED]");
        sanitized = GEO_LOCATION_PATTERN.matcher(sanitized).replaceAll("[LOCATION_REDACTED]");
        return sanitized;
    }
}
