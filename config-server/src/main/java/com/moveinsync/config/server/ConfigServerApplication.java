package com.moveinsync.config.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MoveInSync Hierarchical Tenant Configuration Server.
 *
 * Centralized service managing hierarchical configurations, persistent append-only change logs,
 * atomic change sets, and low-latency SSE push hints.
 */
@SpringBootApplication
@EnableScheduling
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
