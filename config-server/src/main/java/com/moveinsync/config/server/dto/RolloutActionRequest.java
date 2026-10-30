package com.moveinsync.config.server.dto;

/**
 * Request payload for promoting or discarding a staged rollout.
 */
public record RolloutActionRequest(
        String author,
        String reason
) {}
