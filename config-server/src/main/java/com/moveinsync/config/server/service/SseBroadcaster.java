package com.moveinsync.config.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages Server-Sent Events (SSE) connections for live propagation.
 *
 * Concurrency & Scalability Design:
 * 1. Thread Safety: Active client emitters are stored in a {@link CopyOnWriteArrayList}.
 *    Broadcast iterations are safe from ConcurrentModificationException without coarse locking.
 * 2. Push Hint Only: SSE pushes ONLY a tiny hint {@code { "latestVersion": N }}.
 *    This avoids bandwidth saturation during high-frequency changes.
 * 3. Dead Connection Cleanup: Empties are automatically removed via {@code onCompletion},
 *    {@code onTimeout}, and {@code onError} lifecycle hooks, plus active pruning on IOExceptions.
 * 4. Keep-Alive: Sends SSE comments every 15 seconds to prevent intermediate proxy timeouts.
 */
@Service
public class SseBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SseBroadcaster.class);
    private static final Long SSE_TIMEOUT_MS = 300_000L; // 5 minutes

    // Thread-safe collection of active client SSE streams
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * Registers a new client SSE subscriber.
     *
     * @param lastEventId optional Last-Event-ID header sent by reconnecting clients
     * @param currentVersion latest version to send immediately upon connection
     * @return active SseEmitter instance
     */
    public SseEmitter subscribe(String lastEventId, long currentVersion) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // Step 1: Register lifecycle callbacks to guarantee clean emitter removal
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("SSE emitter completed. Active connections: {}", emitters.size());
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            log.debug("SSE emitter timed out. Active connections: {}", emitters.size());
        });
        emitter.onError(ex -> {
            emitters.remove(emitter);
            log.debug("SSE emitter error: {}. Active connections: {}", ex.getMessage(), emitters.size());
        });

        // Step 2: Register in active list
        emitters.add(emitter);
        log.debug("Client subscribed to SSE. Total active: {}", emitters.size());

        // Step 3: Send initial version notification so client immediately knows the current server version
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(currentVersion))
                    .name("version")
                    .data(Map.of("latestVersion", currentVersion)));
        } catch (IOException e) {
            emitters.remove(emitter);
            log.warn("Failed to send initial SSE event, emitter removed: {}", e.getMessage());
        }

        return emitter;
    }

    /**
     * Broadcasts the latest version number to all active subscribers.
     *
     * @param latestVersion new monotonically increasing version
     */
    public void broadcast(long latestVersion) {
        if (emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new ArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                // Send lightweight push hint
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(latestVersion))
                        .name("version")
                        .data(Map.of("latestVersion", latestVersion)));
            } catch (Exception ex) {
                // Network error, client disconnected or broken pipe
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
            log.debug("Pruned {} dead SSE connections. Remaining: {}", deadEmitters.size(), emitters.size());
        }
    }

    /**
     * Periodic heartbeat comment to keep SSE connections alive through reverse proxies and load balancers.
     * Runs every 15 seconds.
     */
    @Scheduled(fixedRate = 15000)
    public void sendHeartbeat() {
        if (emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                // SSE comment starting with colon is ignored by JS EventSource but keeps TCP connection active
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception ex) {
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
        }
    }

    /**
     * Returns the current count of active SSE client connections.
     */
    public int getActiveConnectionCount() {
        return emitters.size();
    }
}
