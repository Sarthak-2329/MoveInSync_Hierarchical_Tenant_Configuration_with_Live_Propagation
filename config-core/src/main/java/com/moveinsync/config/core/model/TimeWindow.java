package com.moveinsync.config.core.model;

import java.io.Serializable;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Represents a daily time window [start, end].
 *
 * Assignment requirement / Assumption:
 * A TIME_WINDOW whose end is not strictly after its start is invalid.
 * For instance, 20:00-06:00 (crossing midnight) is rejected by this rule,
 * and 08:00-08:00 (zero-length) is rejected.
 */
public record TimeWindow(LocalTime start, LocalTime end) implements Serializable {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public TimeWindow {
        Objects.requireNonNull(start, "start time must not be null");
        Objects.requireNonNull(end, "end time must not be null");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(
                    "Invalid time window: end time (" + end + ") must be strictly after start time (" + start + ")"
            );
        }
    }

    /**
     * Checks if the given time falls inclusively within [start, end].
     */
    public boolean contains(LocalTime time) {
        if (time == null) {
            return false;
        }
        return !time.isBefore(start) && !time.isAfter(end);
    }

    /**
     * Parses a string representation such as "09:00-18:00" or "09:00:00-18:00:00".
     */
    public static TimeWindow parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Time window string must not be empty");
        }
        String[] parts = text.split("-");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Time window must follow format 'HH:mm-HH:mm', got: " + text);
        }
        try {
            LocalTime start = LocalTime.parse(parts[0].trim());
            LocalTime end = LocalTime.parse(parts[1].trim());
            return new TimeWindow(start, end);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid time format in window: " + text, ex);
        }
    }

    @Override
    public String toString() {
        return start.format(FORMATTER) + "-" + end.format(FORMATTER);
    }
}
