package com.moveinsync.config.server.dto;

import com.moveinsync.config.core.model.ChangeItem;
import com.moveinsync.config.core.model.ChangeSet;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ApplyChangeSetRequest(
        String author,
        String reason,
        List<ChangeItemDto> items
) {
    public ChangeSet toDomain() {
        List<ChangeItem> domainItems = new ArrayList<>();
        if (items != null) {
            for (ChangeItemDto item : items) {
                domainItems.add(item.toDomain());
            }
        }
        return new ChangeSet(
                UUID.randomUUID().toString(),
                author,
                reason,
                domainItems,
                Instant.now()
        );
    }
}
