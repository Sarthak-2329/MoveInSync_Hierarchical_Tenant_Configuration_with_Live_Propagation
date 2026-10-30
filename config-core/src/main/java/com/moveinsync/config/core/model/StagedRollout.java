package com.moveinsync.config.core.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a staged rollout (canary deployment) of a configuration override
 * targeted to a designated set of offices before company-wide promotion.
 *
 * <p><strong>Invariants:</strong>
 * <ul>
 *   <li>Immutable data carrier (Java 17 Record).</li>
 *   <li>All collections are unmodifiable defensive copies.</li>
 *   <li>State transitions create brand-new instances with updated status and monotonic version.</li>
 * </ul>
 */
public record StagedRollout(
        String id,
        String settingKey,
        String targetClientId,
        Set<String> canaryOfficeIds,
        Level targetLevel,
        Object stagedValue,
        RolloutStatus status,
        String author,
        String reason,
        Instant createdAt,
        long version
) implements Serializable {

    public StagedRollout {
        Objects.requireNonNull(id, "rollout id must not be null");
        Objects.requireNonNull(settingKey, "settingKey must not be null");
        Objects.requireNonNull(targetLevel, "targetLevel must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        canaryOfficeIds = (canaryOfficeIds != null)
                ? Collections.unmodifiableSet(new HashSet<>(canaryOfficeIds))
                : Collections.emptySet();
    }

    public boolean isActive() {
        return status == RolloutStatus.ACTIVE;
    }

    /**
     * Determines whether this active staged rollout applies to a specific evaluation query.
     *
     * @param key query setting key
     * @param clientId query client ID (or null for global)
     * @param officeId query office ID
     * @return true if this active rollout targets this specific office
     */
    public boolean matches(String key, String clientId, String officeId) {
        if (!isActive()) {
            return false;
        }
        if (!settingKey.equals(key)) {
            return false;
        }
        if (targetClientId != null && !targetClientId.equals(clientId)) {
            return false;
        }
        return officeId != null && canaryOfficeIds.contains(officeId);
    }

    /**
     * Produces a promoted instance of this rollout at the specified new version.
     */
    public StagedRollout promote(long newVersion, String promoteAuthor, String promoteReason) {
        return new StagedRollout(
                id,
                settingKey,
                targetClientId,
                canaryOfficeIds,
                targetLevel,
                stagedValue,
                RolloutStatus.PROMOTED,
                promoteAuthor != null ? promoteAuthor : author,
                promoteReason != null ? promoteReason : reason,
                createdAt,
                newVersion
        );
    }

    /**
     * Produces a discarded/aborted instance of this rollout at the specified new version.
     */
    public StagedRollout discard(long newVersion, String discardAuthor, String discardReason) {
        return new StagedRollout(
                id,
                settingKey,
                targetClientId,
                canaryOfficeIds,
                targetLevel,
                stagedValue,
                RolloutStatus.DISCARDED,
                discardAuthor != null ? discardAuthor : author,
                discardReason != null ? discardReason : reason,
                createdAt,
                newVersion
        );
    }
}
