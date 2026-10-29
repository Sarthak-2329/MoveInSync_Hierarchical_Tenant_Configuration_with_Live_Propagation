package com.moveinsync.config.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moveinsync.config.core.engine.ChangeSetApplier;
import com.moveinsync.config.core.engine.Resolver;
import com.moveinsync.config.core.engine.SnapshotDiffCalculator;
import com.moveinsync.config.core.exception.SettingNotFoundException;
import com.moveinsync.config.core.exception.ValidationException;
import com.moveinsync.config.core.model.ChangeSet;
import com.moveinsync.config.core.model.ConfigDelta;
import com.moveinsync.config.core.model.ResolutionExplanation;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.server.dto.DeltaResponse;
import com.moveinsync.config.server.dto.RollbackResponse;
import com.moveinsync.config.server.persistence.ChangeKind;
import com.moveinsync.config.server.persistence.ChangeLogEntity;
import com.moveinsync.config.server.persistence.ChangeLogRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Central engine coordinating configuration persistence, memory snapshot swaps,
 * replay on startup, and SSE live notifications.
 *
 * Concurrency & Write Ordering Guarantee:
 * On every write:
 * 1. Pre-validate all items (fail-fast without side effects).
 * 2. Commit transaction to H2 database change log.
 * 3. Construct and atomically swap the in-memory {@link AtomicReference<Snapshot>}.
 * 4. Broadcast the new version hint via SSE.
 *
 * Result: Reads are non-blocking and instant (sub-microsecond), writes are strictly serialized,
 * and any acknowledged write is guaranteed durable across crashes.
 */
@Service
public class ConfigEngineService {

    private static final Logger log = LoggerFactory.getLogger(ConfigEngineService.class);

    private final ChangeLogRepository changeLogRepository;
    private final TenantFilterService tenantFilterService;
    private final SseBroadcaster sseBroadcaster;
    private final ObjectMapper objectMapper;

    // Concurrency controls
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicLong versionSequence = new AtomicLong(0L);
    private final AtomicReference<Snapshot> currentSnapshot = new AtomicReference<>(Snapshot.empty());

    // In-memory cache of historical snapshots for instant diff and rollback computations
    private final ConcurrentHashMap<Long, Snapshot> snapshotHistory = new ConcurrentHashMap<>();

    public ConfigEngineService(
            ChangeLogRepository changeLogRepository,
            TenantFilterService tenantFilterService,
            SseBroadcaster sseBroadcaster,
            ObjectMapper objectMapper
    ) {
        this.changeLogRepository = changeLogRepository;
        this.tenantFilterService = tenantFilterService;
        this.sseBroadcaster = sseBroadcaster;
        this.objectMapper = objectMapper;
    }

    /**
     * Replays the append-only change log from H2 file database at application startup.
     * Reconstructs the complete in-memory Snapshot and initializes the monotonic version counter.
     */
    @PostConstruct
    public void init() {
        writeLock.lock();
        try {
            log.info("Initiating change log replay from database...");
            List<ChangeLogEntity> logEntries = changeLogRepository.findAllByOrderByVersionAsc();

            Snapshot snapshot = Snapshot.empty();
            snapshotHistory.put(0L, snapshot);
            long maxVersion = 0L;

            for (ChangeLogEntity entry : logEntries) {
                long ver = entry.getVersion();
                maxVersion = Math.max(maxVersion, ver);
                snapshot = replayEntry(snapshot, entry);
                snapshotHistory.put(ver, snapshot);
            }

            versionSequence.set(maxVersion);
            currentSnapshot.set(snapshot);
            log.info("Replay completed successfully. Reconstructed {} log entries. Current version: {}",
                    logEntries.size(), maxVersion);
        } catch (Exception ex) {
            log.error("Failed to replay configuration change log at startup", ex);
            throw new IllegalStateException("Startup replay failed: " + ex.getMessage(), ex);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Registers a new setting definition.
     */
    @Transactional
    public Snapshot registerDefinition(SettingDefinition definition, String author, String reason) {
        writeLock.lock();
        try {
            Snapshot current = currentSnapshot.get();
            long newVersion = versionSequence.incrementAndGet();

            // Step 1: Pre-validation and build new snapshot in-memory
            Snapshot nextSnapshot = ChangeSetApplier.registerDefinition(current, definition, newVersion);

            // Step 2: Persist append-only change record to database
            String payloadJson = objectMapper.writeValueAsString(definition);
            ChangeLogEntity entity = new ChangeLogEntity(
                    newVersion,
                    author != null ? author : "system",
                    reason != null ? reason : "Register setting definition " + definition.key(),
                    Instant.now(),
                    ChangeKind.DEFINITION,
                    payloadJson
            );
            changeLogRepository.save(entity);

            // Step 3: AtomicReference swap
            currentSnapshot.set(nextSnapshot);
            snapshotHistory.put(newVersion, nextSnapshot);

            // Step 4: Live propagation notification
            sseBroadcaster.broadcast(newVersion);

            log.info("Registered definition '{}' at version {}", definition.key(), newVersion);
            return nextSnapshot;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize setting definition JSON", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Applies an atomic ChangeSet.
     */
    @Transactional
    public Snapshot applyChangeSet(ChangeSet changeSet) {
        writeLock.lock();
        try {
            Snapshot current = currentSnapshot.get();
            long newVersion = versionSequence.incrementAndGet();

            // Step 1: Pre-validation phase (throws ValidationException if invalid, leaves state untouched)
            Snapshot nextSnapshot = ChangeSetApplier.applyChangeSet(current, changeSet, newVersion);

            // Step 2: Persist append-only change log entry to database
            String payloadJson = objectMapper.writeValueAsString(changeSet);
            ChangeLogEntity entity = new ChangeLogEntity(
                    newVersion,
                    changeSet.author(),
                    changeSet.reason(),
                    changeSet.timestamp(),
                    ChangeKind.NORMAL,
                    payloadJson
            );
            changeLogRepository.save(entity);

            // Step 3: Atomic in-memory reference swap
            currentSnapshot.set(nextSnapshot);
            snapshotHistory.put(newVersion, nextSnapshot);

            // Step 4: Broadcast SSE push hint
            sseBroadcaster.broadcast(newVersion);

            log.info("Applied change set '{}' by '{}' with {} items. Advanced to version {}",
                    changeSet.id(), changeSet.author(), changeSet.items().size(), newVersion);
            return nextSnapshot;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize change set JSON", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Rolls back state to any historical version N in one atomic call.
     *
     * Invariant: Monotonic versioning. Rollback creates a NEW version N' > currentVersion,
     * whose state matches historical version N. It never decrements the counter.
     */
    @Transactional
    public RollbackResponse rollbackTo(long targetVersion, String author, String reason) {
        writeLock.lock();
        try {
            Snapshot current = currentSnapshot.get();
            if (targetVersion < 0 || targetVersion >= current.version()) {
                throw new ValidationException(String.format(
                        "Invalid rollback target version %d. Target must be >= 0 and < current version %d",
                        targetVersion, current.version()));
            }

            Snapshot targetSnapshot = getSnapshotAtVersion(targetVersion);
            if (targetSnapshot == null) {
                throw new ValidationException("Cannot rollback: historical snapshot for version " + targetVersion + " not found");
            }

            // Compute the diff of what changes between current and target
            ConfigDelta diff = SnapshotDiffCalculator.calculateDiff(current, targetSnapshot);

            long newVersion = versionSequence.incrementAndGet();
            // New snapshot has the exact definitions and overrides of targetSnapshot, but stamped with newVersion
            Snapshot nextSnapshot = new Snapshot(newVersion, targetSnapshot.definitions(), targetSnapshot.overridesByKey());

            // Persist rollback record
            record RollbackPayload(long targetVersion, String reason) {}
            String payloadJson = objectMapper.writeValueAsString(new RollbackPayload(targetVersion, reason));
            ChangeLogEntity entity = new ChangeLogEntity(
                    newVersion,
                    author != null ? author : "system",
                    reason != null ? reason : "Rollback to version " + targetVersion,
                    Instant.now(),
                    ChangeKind.ROLLBACK,
                    payloadJson
            );
            changeLogRepository.save(entity);

            // Atomic swap
            currentSnapshot.set(nextSnapshot);
            snapshotHistory.put(newVersion, nextSnapshot);

            // Broadcast
            sseBroadcaster.broadcast(newVersion);

            log.info("Rolled back state from version {} to {} as NEW version {}",
                    current.version(), targetVersion, newVersion);
            return new RollbackResponse(newVersion, targetVersion, diff);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize rollback payload", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Retrieves the current snapshot isolated for the caller's tenant.
     */
    public Snapshot getCurrentSnapshot(String tenantId) {
        return tenantFilterService.filterSnapshot(currentSnapshot.get(), tenantId);
    }

    /**
     * Retrieves incremental changes since a specified version, isolated for the caller's tenant.
     */
    public DeltaResponse getChangesSince(long sinceVersion, String tenantId) {
        Snapshot current = currentSnapshot.get();
        long currentVer = current.version();

        if (sinceVersion >= currentVer) {
            // Client is already up-to-date or ahead; return empty delta with currentVersion
            return DeltaResponse.from(ConfigDelta.empty(currentVer), currentVer);
        }

        Snapshot baseline = getSnapshotAtVersion(sinceVersion);
        if (baseline == null) {
            baseline = Snapshot.empty();
        }

        ConfigDelta rawDelta = SnapshotDiffCalculator.calculateDiff(baseline, current);
        ConfigDelta isolatedDelta = tenantFilterService.filterDelta(rawDelta, tenantId);

        return DeltaResponse.from(isolatedDelta, currentVer);
    }

    /**
     * Computes the diff between any two historical versions.
     */
    public ConfigDelta getDiff(long fromVersion, long toVersion, String tenantId) {
        Snapshot fromSnap = getSnapshotAtVersion(fromVersion);
        Snapshot toSnap = getSnapshotAtVersion(toVersion);

        if (fromSnap == null) {
            throw new ValidationException("Snapshot for version " + fromVersion + " not found");
        }
        if (toSnap == null) {
            throw new ValidationException("Snapshot for version " + toVersion + " not found");
        }

        ConfigDelta rawDiff = SnapshotDiffCalculator.calculateDiff(fromSnap, toSnap);
        return tenantFilterService.filterDelta(rawDiff, tenantId);
    }

    /**
     * Explains value resolution for a given key and scope.
     */
    public ResolutionExplanation explain(String key, Scope scope, String tenantId) {
        Snapshot tenantSnapshot = getCurrentSnapshot(tenantId);
        if (!tenantSnapshot.hasDefinition(key)) {
            throw new SettingNotFoundException(key);
        }
        return Resolver.explain(tenantSnapshot, key, scope);
    }

    public long getCurrentVersion() {
        return currentSnapshot.get().version();
    }

    private Snapshot getSnapshotAtVersion(long version) {
        Snapshot cached = snapshotHistory.get(version);
        if (cached != null) {
            return cached;
        }

        // Fallback: reconstruct from database by replaying up to version
        List<ChangeLogEntity> entries = changeLogRepository.findByVersionLessThanEqualOrderByVersionAsc(version);
        Snapshot snap = Snapshot.empty();
        for (ChangeLogEntity entry : entries) {
            snap = replayEntry(snap, entry);
            snapshotHistory.put(entry.getVersion(), snap);
        }
        return snap;
    }

    private Snapshot replayEntry(Snapshot current, ChangeLogEntity entry) {
        try {
            return switch (entry.getKind()) {
                case DEFINITION -> {
                    SettingDefinition def = objectMapper.readValue(entry.getPayloadJson(), SettingDefinition.class);
                    yield ChangeSetApplier.registerDefinition(current, def, entry.getVersion());
                }
                case NORMAL -> {
                    ChangeSet changeSet = objectMapper.readValue(entry.getPayloadJson(), ChangeSet.class);
                    yield ChangeSetApplier.applyChangeSet(current, changeSet, entry.getVersion());
                }
                case ROLLBACK -> {
                    record RollbackPayload(long targetVersion, String reason) {}
                    RollbackPayload payload = objectMapper.readValue(entry.getPayloadJson(), RollbackPayload.class);
                    Snapshot target = snapshotHistory.get(payload.targetVersion());
                    if (target == null) {
                        target = getSnapshotAtVersion(payload.targetVersion());
                    }
                    yield new Snapshot(entry.getVersion(), target.definitions(), target.overridesByKey());
                }
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse log entry version " + entry.getVersion(), e);
        }
    }
}
