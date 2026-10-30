package com.moveinsync.config.client;

import com.moveinsync.config.client.dto.ClientDeltaResponse;
import com.moveinsync.config.client.store.DiskSnapshotPersistence;
import com.moveinsync.config.client.store.SnapshotStore;
import com.moveinsync.config.client.sync.HttpConfigSync;
import com.moveinsync.config.client.sync.SseSubscriber;
import com.moveinsync.config.core.engine.ChangeSetApplier;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Concrete coordinator for the MoveInSync Configuration Client.
 *
 * <p><strong>Key Concurrency & Architectural Highlights:</strong>
 * 1. <strong>Zero-Latency Read Path:</strong> Reads capture {@link SnapshotStore#get()} and return
 *    an immutable {@link ConfigViewImpl} with zero locking and zero blocking.
 * 2. <strong>Atomic State Transitions:</strong> Updates downloaded via delta or full snapshot
 *    construct a new immutable {@code Snapshot} and swap the active reference in one atomic CAS.
 * 3. <strong>Offline Survival:</strong> Bootstraps from local disk cache if the config server
 *    is unreachable at application startup.
 * 4. <strong>Live Propagation & Healing:</strong> Connects to SSE stream for sub-second push hints,
 *    complemented by jittered background polling to heal dropped connections or missed pushes.
 */
public final class ConfigClientImpl implements ConfigClient {

    private static final Logger log = LoggerFactory.getLogger(ConfigClientImpl.class);

    private final ClientConfig config;
    private final SnapshotStore snapshotStore;
    private final DiskSnapshotPersistence diskPersistence;
    private final HttpConfigSync httpSync;
    private final SseSubscriber sseSubscriber;
    private final ScheduledExecutorService pollerExecutor;
    private final boolean ownExecutor;

    private final ReentrantLock syncLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ConfigClientImpl(ClientConfig config) {
        this.config = config;
        this.diskPersistence = new DiskSnapshotPersistence(config.getDiskSnapshotPath());

        // Step 1: Bootstrap from local disk cache if available
        Optional<Snapshot> diskSnapshot = diskPersistence.load();
        this.snapshotStore = diskSnapshot.map(SnapshotStore::new).orElseGet(SnapshotStore::new);

        // Step 2: Initialize HTTP sync engine
        this.httpSync = new HttpConfigSync(config.getHttpClient());

        // Step 3: Thread factory / executor for background scheduled poller
        if (config.getScheduledExecutor() != null) {
            this.pollerExecutor = config.getScheduledExecutor();
            this.ownExecutor = false;
        } else {
            ThreadFactory threadFactory = r -> {
                Thread t = new Thread(r, "config-client-poller");
                t.setDaemon(true);
                return t;
            };
            this.pollerExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);
            this.ownExecutor = true;
        }

        // Step 4: Initialize SSE subscriber with on-demand sync callback
        this.sseSubscriber = new SseSubscriber(
                config.getServerUrl(),
                config.getApiKey(),
                config.getTenantId(),
                config.getHttpClient(),
                snapshotStore::getVersion,
                this::onVersionHintReceived
        );

        // Step 5: Start background sync cycles if enabled
        if (config.isStartBackgroundSync()) {
            // Initial eager synchronization
            tryInitialSync();

            // Start SSE live subscription
            sseSubscriber.start();

            // Schedule periodic poll loop with jitter
            scheduleNextPoll();
        }
    }

    private void tryInitialSync() {
        try {
            sync();
        } catch (Exception ex) {
            log.warn("Initial network sync failed for server {}: {}. Operating with local snapshot (version: {})",
                    config.getServerUrl(), ex.getMessage(), snapshotStore.getVersion());
        }
    }

    private void onVersionHintReceived(long hintVersion) {
        if (hintVersion > snapshotStore.getVersion() && !closed.get()) {
            log.debug("SSE version hint {} > local version {}, triggering immediate delta pull",
                    hintVersion, snapshotStore.getVersion());
            // Asynchronously dispatch pull task so SSE reader thread remains non-blocking
            pollerExecutor.submit(this::sync);
        }
    }

    private void scheduleNextPoll() {
        if (closed.get()) {
            return;
        }

        // Calculate jitter: base interval + random(0, maxJitter)
        long baseMillis = config.getPollInterval().toMillis();
        long maxJitterMillis = config.getMaxJitter().toMillis();
        long jitterMillis = (maxJitterMillis > 0) ? ThreadLocalRandom.current().nextLong(maxJitterMillis) : 0L;
        long totalDelayMillis = baseMillis + jitterMillis;

        pollerExecutor.schedule(() -> {
            try {
                if (!closed.get()) {
                    sync();
                }
            } catch (Exception ex) {
                log.debug("Background poll sync encountered an error: {}", ex.getMessage());
            } finally {
                scheduleNextPoll();
            }
        }, totalDelayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public ConfigView view(Scope scope) {
        return new ConfigViewImpl(snapshotStore.get(), scope);
    }

    @Override
    public long getCurrentVersion() {
        return snapshotStore.getVersion();
    }

    @Override
    public Snapshot getSnapshot() {
        return snapshotStore.get();
    }

    @Override
    public boolean sync() {
        if (closed.get()) {
            return false;
        }

        // Ensure only one sync network call runs at a time per client
        if (!syncLock.tryLock()) {
            log.debug("Sync cycle already in progress, skipping concurrent trigger");
            return false;
        }

        try {
            long currentVersion = snapshotStore.getVersion();

            if (currentVersion == 0) {
                // Initial bootstrapping: Fetch complete snapshot
                Snapshot remoteSnapshot = httpSync.fetchFullSnapshot(
                        config.getServerUrl(),
                        config.getApiKey(),
                        config.getTenantId()
                );
                boolean updated = snapshotStore.updateIfNewer(remoteSnapshot);
                if (updated) {
                    diskPersistence.save(snapshotStore.get());
                    log.info("Initialized local configuration to snapshot version {}", remoteSnapshot.version());
                    return true;
                }
            } else {
                // Incremental synchronization: Request only what changed since our local version
                ClientDeltaResponse delta = httpSync.fetchDelta(
                        config.getServerUrl(),
                        config.getApiKey(),
                        config.getTenantId(),
                        currentVersion
                );

                if (delta.toVersion() > currentVersion) {
                    Snapshot current = snapshotStore.get();
                    Snapshot nextSnapshot = ChangeSetApplier.applyDelta(current, delta.toDomainDelta());

                    // Handle tenant version gaps: advance to server's currentVersion if higher
                    if (delta.currentVersion() > nextSnapshot.version()) {
                        nextSnapshot = new Snapshot(
                                delta.currentVersion(),
                                nextSnapshot.definitions(),
                                nextSnapshot.overridesByKey()
                        );
                    }

                    boolean updated = snapshotStore.updateIfNewer(nextSnapshot);
                    if (updated) {
                        diskPersistence.save(snapshotStore.get());
                        log.info("Applied delta: local snapshot advanced from version {} to {}",
                                currentVersion, nextSnapshot.version());
                        return true;
                    }
                } else if (delta.currentVersion() > currentVersion) {
                    // No overrides changed for this specific tenant, but global clock moved forward
                    boolean advanced = snapshotStore.advanceVersionIfNewer(delta.currentVersion());
                    if (advanced) {
                        diskPersistence.save(snapshotStore.get());
                        log.debug("Advanced local version pointer from {} to {} (tenant gap)",
                                currentVersion, delta.currentVersion());
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception ex) {
            log.warn("Configuration sync failed with server {}: {}", config.getServerUrl(), ex.getMessage());
            return false;
        } finally {
            syncLock.unlock();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.info("Closing ConfigClient for {}", config.getServerUrl());
            try {
                sseSubscriber.close();
            } catch (Exception e) {
                log.warn("Error closing SSE subscriber: {}", e.getMessage());
            }
            if (ownExecutor) {
                pollerExecutor.shutdownNow();
                try {
                    pollerExecutor.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public SnapshotStore getSnapshotStore() {
        return snapshotStore;
    }

    public DiskSnapshotPersistence getDiskPersistence() {
        return diskPersistence;
    }
}
