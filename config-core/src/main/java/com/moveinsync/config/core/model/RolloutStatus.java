package com.moveinsync.config.core.model;

/**
 * Lifecycle state of a staged configuration rollout.
 */
public enum RolloutStatus {
    /**
     * The rollout is actively serving canary traffic to designated candidate offices.
     */
    ACTIVE,

    /**
     * The rollout has been approved and promoted to the target hierarchy level company-wide.
     */
    PROMOTED,

    /**
     * The rollout has been discarded/aborted; canary offices immediately revert to baseline.
     */
    DISCARDED
}
