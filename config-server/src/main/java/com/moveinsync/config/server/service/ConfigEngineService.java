package com.moveinsync.config.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.core.engine.ChangeSetApplier;
import com.moveinsync.config.core.engine.Resolver;
import com.moveinsync.config.core.engine.SnapshotDiffCalculator;
import com.moveinsync.config.core.exception.SettingNotFoundException;
import com.moveinsync.config.core.exception.ValidationException;
import com.moveinsync.config.core.model.ChangeSet;
import com.moveinsync.config.core.model.ConfigDelta;
import com.moveinsync.config.core.model.ResolutionExplanation;
import com.moveinsync.config.core.model.RolloutStatus;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.core.model.StagedRollout;
import com.moveinsync.config.server.cache.TenantCacheService;
import com.moveinsync.config.server.dto.CreateRolloutRequest;
import com.moveinsync.config.server.dto.DeltaResponse;
import com.moveinsync.config.server.dto.RollbackResponse;
import com.moveinsync.config.server.metrics.ConfigMetrics;
import com.moveinsync.config.server.persistence.ChangeKind;
import com.moveinsync.config.server.persistence.ChangeLogEntity;
import com.moveinsync.config.server.persistence.ChangeLogRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Central engine coordinating configuration persistence, memory snapshot swaps,
 * replay on startup, and SSE live notifications.
 *
 * <p><strong>Concurrency & Write Ordering Guarantee:</strong>
 * On every write:
 * <ol>
 *   <li>Pre-validate all items (fail-fast without side effects).</li>
 *   <li>Commit transaction to H2 database change log.</li>
 *   <li>Construct and atomically swap the in-memory {@link AtomicReference<Snapshot>}.</li>
 *   <li>Broadcast the new version hint via SSE.</li>
 * </ol>
 *
 * <p>Result: Reads are non-blocking and instant (sub-microsecond), writes are strictly serialized,
 * and any acknowledged write is guaranteed durable across crashes.
 */
@Service
public class ConfigEngineService {

    private static final Logger log = LoggerFactory.getLogger(ConfigEngineService.class);

    private final ChangeLogRepository changeLogRepository;
    private final TenantFilterService tenantFilterService;
    private final TenantCacheService tenantCacheService;
    private final SseBroadcaster sseBroadcaster;
    private final ConfigMetrics configMetrics;
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
            TenantCacheService tenantCacheService,
            SseBroadcaster sseBroadcaster,
            ConfigMetrics configMetrics,
            ObjectMapper objectMapper
    ) {
        this.changeLogRepository = changeLogRepository;
        this.tenantFilterService = tenantFilterService;
        this.tenantCacheService = tenantCacheService;
        this.sseBroadcaster = sseBroadcaster;
        this.configMetrics = configMetrics;
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
            tenantCacheService.invalidateAll();
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

            // Step 4: Live propagation notification & metrics
            sseBroadcaster.broadcast(newVersion);
            configMetrics.recordChangeApplied();

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

            // Step 4: Broadcast SSE push hint & record metric
            sseBroadcaster.broadcast(newVersion);
            configMetrics.recordChangeApplied();

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
     * <p><strong>Invariant:</strong> Monotonic versioning. Rollback creates a NEW version N' > currentVersion,
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
            // New snapshot has the exact definitions, overrides, and rollouts of targetSnapshot, but stamped with newVersion
            Snapshot nextSnapshot = new Snapshot(newVersion, targetSnapshot.definitions(), targetSnapshot.overridesByKey(), targetSnapshot.stagedRollouts());

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

            // Broadcast & metrics
            sseBroadcaster.broadcast(newVersion);
            configMetrics.recordChangeApplied();

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
     * Retrieves the current snapshot isolated for the caller's tenant with Caffeine caching.
     */
    public Snapshot getCurrentSnapshot(String tenantId) {
        Snapshot master = currentSnapshot.get();
        if (tenantId == null || tenantId.isBlank() || "*".equals(tenantId)) {
            return master;
        }
        return tenantCacheService.getSnapshot(tenantId, master.version(), tid ->
                tenantFilterService.filterSnapshot(master, tid)
        );
    }

    /**
     * Retrieves incremental changes since a specified version, isolated for the caller's tenant with Caffeine caching.
     */
    public DeltaResponse getChangesSince(long sinceVersion, String tenantId) {
        configMetrics.recordDeltaRequest();
        Snapshot current = currentSnapshot.get();
        long currentVer = current.version();

        if (sinceVersion >= currentVer) {
            // Client is already up-to-date or ahead; return empty delta with currentVersion
            return DeltaResponse.from(ConfigDelta.empty(currentVer), currentVer);
        }

        if (sinceVersion > 0) {
            configMetrics.recordPollHeal();
        }

        Snapshot baseline = getSnapshotAtVersion(sinceVersion);
        if (baseline == null) {
            baseline = Snapshot.empty();
        }

        ConfigDelta rawDelta = SnapshotDiffCalculator.calculateDiff(baseline, current);
        ConfigDelta isolatedDelta = tenantCacheService.getDelta(tenantId, sinceVersion, currentVer, tid ->
                tenantFilterService.filterDelta(rawDelta, tid)
        );

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
                    yield new Snapshot(entry.getVersion(), target.definitions(), target.overridesByKey(), target.stagedRollouts());
                }
                case STAGED_ROLLOUT -> {
                    StagedRollout rollout = objectMapper.readValue(entry.getPayloadJson(), StagedRollout.class);
                    yield ChangeSetApplier.applyStagedRollout(current, rollout, entry.getVersion());
                }
                case ROLLOUT_PROMOTE -> {
                    record RolloutActionPayload(String rolloutId, String reason) {}
                    RolloutActionPayload payload = objectMapper.readValue(entry.getPayloadJson(), RolloutActionPayload.class);
                    yield ChangeSetApplier.promoteStagedRollout(current, payload.rolloutId(), entry.getVersion(), entry.getAuthor(), payload.reason());
                }
                case ROLLOUT_DISCARD -> {
                    record RolloutActionPayload(String rolloutId, String reason) {}
                    RolloutActionPayload payload = objectMapper.readValue(entry.getPayloadJson(), RolloutActionPayload.class);
                    yield ChangeSetApplier.discardStagedRollout(current, payload.rolloutId(), entry.getVersion(), entry.getAuthor(), payload.reason());
                }
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse log entry version " + entry.getVersion(), e);
        }
    }

    /**
     * Creates and activates a staged configuration rollout targeting candidate offices.
     */
    @Transactional
    public StagedRollout createStagedRollout(CreateRolloutRequest request, String author) {
        writeLock.lock();
        try {
            Snapshot current = currentSnapshot.get();
            long newVersion = versionSequence.incrementAndGet();

            String rolloutId = (request.id() != null && !request.id().isBlank())
                    ? request.id()
                    : "ro-" + UUID.randomUUID().toString().substring(0, 8);

            StagedRollout rollout = new StagedRollout(
                    rolloutId,
                    request.settingKey(),
                    request.targetClientId(),
                    request.canaryOfficeIds(),
                    request.targetLevel(),
                    request.stagedValue(),
                    RolloutStatus.ACTIVE,
                    author != null ? author : "system",
                    request.reason() != null ? request.reason() : "Staged rollout to canary offices",
                    Instant.now(),
                    newVersion
            );

            // Step 1: Pre-validate and construct new snapshot
            Snapshot nextSnapshot = ChangeSetApplier.applyStagedRollout(current, rollout, newVersion);

            // Step 2: Persist change record to H2 database
            String payloadJson = objectMapper.writeValueAsString(rollout);
            ChangeLogEntity entity = new ChangeLogEntity(
                    newVersion,
                    rollout.author(),
                    rollout.reason(),
                    rollout.createdAt(),
                    ChangeKind.STAGED_ROLLOUT,
                    payloadJson
            );
            changeLogRepository.save(entity);

            // Step 3: Atomic swap & history cache update
            currentSnapshot.set(nextSnapshot);
            snapshotHistory.put(newVersion, nextSnapshot);
            tenantCacheService.invalidateAll();

            // Step 4: Broadcast SSE push hint & record metric
            sseBroadcaster.broadcast(newVersion);
            configMetrics.recordChangeApplied();

            log.info("Created staged rollout '{}' for setting '{}' targeting offices {} at version {}",
                    rollout.id(), rollout.settingKey(), rollout.canaryOfficeIds(), newVersion);
            return nextSnapshot.getStagedRollout(rollout.id());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize staged rollout JSON", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Promotes an active staged rollout to the target level company-wide.
     */
    @Transactional
    public StagedRollout promoteRollout(String rolloutId, String author, String reason) {
        writeLock.lock();
        try {
            Snapshot current = currentSnapshot.get();
            long newVersion = versionSequence.incrementAndGet();

            String effectiveAuthor = author != null ? author : "system";
            String effectiveReason = reason != null ? reason : "Promote staged rollout " + rolloutId;

            // Step 1: Pre-validate and construct new snapshot with permanent override
            Snapshot nextSnapshot = ChangeSetApplier.promoteStagedRollout(current, rolloutId, newVersion, effectiveAuthor, effectiveReason);

            // Step 2: Persist promotion log entry
            record RolloutActionPayload(String rolloutId, String reason) {}
            String payloadJson = objectMapper.writeValueAsString(new RolloutActionPayload(rolloutId, effectiveReason));
            ChangeLogEntity entity = new ChangeLogEntity(
                    newVersion,
                    effectiveAuthor,
                    effectiveReason,
                    Instant.now(),
                    ChangeKind.ROLLOUT_PROMOTE,
                    payloadJson
            );
            changeLogRepository.save(entity);

            // Step 3: Atomic swap
            currentSnapshot.set(nextSnapshot);
            snapshotHistory.put(newVersion, nextSnapshot);
            tenantCacheService.invalidateAll();

            // Step 4: Broadcast & metric
            sseBroadcaster.broadcast(newVersion);
            configMetrics.recordChangeApplied();

            log.info("Promoted staged rollout '{}' to company-wide target level at version {}", rolloutId, newVersion);
            return nextSnapshot.getStagedRollout(rolloutId);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize rollout promotion JSON", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Discards an active staged rollout; canary offices immediately revert to baseline.
     */
    @Transactional
    public StagedRollout discardRollout(String rolloutId, String author, String reason) {
        writeLock.lock();
        try {
            Snapshot current = currentSnapshot.get();
            long newVersion = versionSequence.incrementAndGet();

            String effectiveAuthor = author != null ? author : "system";
            String effectiveReason = reason != null ? reason : "Discard staged rollout " + rolloutId;

            // Step 1: Pre-validate and construct new snapshot
            Snapshot nextSnapshot = ChangeSetApplier.discardStagedRollout(current, rolloutId, newVersion, effectiveAuthor, effectiveReason);

            // Step 2: Persist discard log entry
            record RolloutActionPayload(String rolloutId, String reason) {}
            String payloadJson = objectMapper.writeValueAsString(new RolloutActionPayload(rolloutId, effectiveReason));
            ChangeLogEntity entity = new ChangeLogEntity(
                    newVersion,
                    effectiveAuthor,
                    effectiveReason,
                    Instant.now(),
                    ChangeKind.ROLLOUT_DISCARD,
                    payloadJson
            );
            changeLogRepository.save(entity);

            // Step 3: Atomic swap
            currentSnapshot.set(nextSnapshot);
            snapshotHistory.put(newVersion, nextSnapshot);
            tenantCacheService.invalidateAll();

            // Step 4: Broadcast & metric
            sseBroadcaster.broadcast(newVersion);
            configMetrics.recordChangeApplied();

            log.info("Discarded staged rollout '{}' at version {}", rolloutId, newVersion);
            return nextSnapshot.getStagedRollout(rolloutId);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize rollout discard JSON", e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Retrieves all staged rollouts accessible to the caller's tenant.
     */
    public List<StagedRollout> getStagedRollouts(String tenantId, RolloutStatus status) {
        Snapshot tenantSnapshot = getCurrentSnapshot(tenantId);
        return tenantSnapshot.stagedRollouts().values().stream()
                .filter(r -> status == null || r.status() == status)
                .toList();
    }

    /**
     * Retrieves a single staged rollout by ID if accessible to caller's tenant.
     */
    public StagedRollout getStagedRollout(String rolloutId, String tenantId) {
        Snapshot tenantSnapshot = getCurrentSnapshot(tenantId);
        StagedRollout rollout = tenantSnapshot.getStagedRollout(rolloutId);
        if (rollout == null) {
            throw new ValidationException("Staged rollout '" + rolloutId + "' not found");
        }
        return rollout;
    }
}
