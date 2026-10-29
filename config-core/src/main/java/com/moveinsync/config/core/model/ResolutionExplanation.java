package com.moveinsync.config.core.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Detailed explanation of how a setting value was resolved.
 *
 * Shows every hierarchy level evaluated in priority order (SHIFT -> OFFICE -> CLIENT -> GLOBAL -> DEFAULT),
 * the candidate value at each level (if present), and which level ultimately won.
 */
public record ResolutionExplanation(
        String key,
        Scope queryScope,
        Level winningLevel,
        Object winningValue,
        List<CandidateEvaluation> evaluations
) implements Serializable {

    public ResolutionExplanation {
        evaluations = (evaluations != null)
                ? Collections.unmodifiableList(List.copyOf(evaluations))
                : Collections.emptyList();
    }

    /**
     * Audit record for a single hierarchy level candidate evaluation.
     */
    public record CandidateEvaluation(
            Level level,
            Scope evaluatedScope,
            Object candidateValue,
            boolean hasValue,
            boolean isWinner,
            String reason
    ) implements Serializable {}
}
