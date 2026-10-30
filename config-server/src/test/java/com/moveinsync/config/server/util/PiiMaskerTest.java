package com.moveinsync.config.server.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class PiiMaskerTest {

    @Test
    @DisplayName("PiiMasker masks email addresses in messages")
    void masksEmailAddresses() {
        String msg = "User john.doe@company.com attempted to set cutoff to -10";
        String masked = PiiMasker.mask(msg);
        assertThat(masked).isEqualTo("User [EMAIL_REDACTED] attempted to set cutoff to -10");
        assertThat(masked).doesNotContain("john.doe@company.com");
    }

    @Test
    @DisplayName("PiiMasker masks standard phone numbers")
    void masksPhoneNumbers() {
        String msg1 = "Call support at +91 9876543210 for help";
        String msg2 = "Emergency contact: 415-555-2671";
        assertThat(PiiMasker.mask(msg1)).doesNotContain("9876543210");
        assertThat(PiiMasker.mask(msg1)).contains("[PHONE_REDACTED]");

        assertThat(PiiMasker.mask(msg2)).doesNotContain("415-555-2671");
        assertThat(PiiMasker.mask(msg2)).contains("[PHONE_REDACTED]");
    }

    @Test
    @DisplayName("PiiMasker masks home/office GPS latitude and longitude coordinates")
    void masksGeoCoordinates() {
        String msg = "Drop-off coordinates 12.9716, 77.5946 out of bounds";
        String masked = PiiMasker.mask(msg);
        assertThat(masked).doesNotContain("12.9716");
        assertThat(masked).doesNotContain("77.5946");
        assertThat(masked).contains("[LOCATION_REDACTED]");
    }

    @Test
    @DisplayName("PiiMasker preserves regular error messages without PII")
    void preservesSafeMessages() {
        String msg = "Setting 'booking.cutoff.minutes' value -30.00 is less than allowed minimum 0.00";
        assertThat(PiiMasker.mask(msg)).isEqualTo(msg);
    }
}
