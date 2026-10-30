package com.moveinsync.config.client.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackoffManagerTest {

    @Test
    @DisplayName("Delay is bounded between 0 and min(maxDelay, baseDelay * 2^attempt)")
    void delayBoundaries() {
        Duration base = Duration.ofMillis(100);
        Duration max = Duration.ofSeconds(2);
        BackoffManager manager = new BackoffManager(base, max);

        for (int attempt = 0; attempt < 20; attempt++) {
            Duration delay = manager.calculateDelay(attempt);
            assertThat(delay.toMillis()).isGreaterThanOrEqualTo(0);
            assertThat(delay.toMillis()).isLessThanOrEqualTo(max.toMillis());
        }
    }

    @Test
    @DisplayName("Guards against illegal arguments")
    void illegalArguments() {
        assertThatThrownBy(() -> new BackoffManager(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new BackoffManager(Duration.ofSeconds(5), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
