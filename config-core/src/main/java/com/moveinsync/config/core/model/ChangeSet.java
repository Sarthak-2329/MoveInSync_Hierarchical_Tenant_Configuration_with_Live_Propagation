package com.moveinsync.config.core.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An atomic batch of configuration mutations.
 *
 * All items within a ChangeSet are validated and committed together.
 * Either all items take effect and produce a new snapshot version,
 * or the entire change set is rejected.
 */
public record ChangeSet(
        String id,
        String author,
        String reason,
        List<ChangeItem> items,
        Instant timestamp
) implements Serializable {

    public ChangeSet {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ChangeSet id must not be blank");
        }
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("ChangeSet author must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("ChangeSet reason must not be blank");
        }
        Objects.requireNonNull(items, "ChangeSet items must not be null");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("ChangeSet must contain at least one item");
        }
        timestamp = (timestamp != null) ? timestamp : Instant.now();
        items = Collections.unmodifiableList(List.copyOf(items));
    }
}
