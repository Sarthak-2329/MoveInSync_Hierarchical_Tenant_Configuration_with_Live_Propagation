package com.moveinsync.config.server.dto;

import com.moveinsync.config.core.model.ChangeItem;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.Scope;

public record ChangeItemDto(
        String key,
        ScopeDto scope,
        Level level,
        Object newValue,
        boolean isDelete
) {
    public ChangeItem toDomain() {
        Scope domainScope = (scope != null) ? scope.toDomain() : Scope.global();
        return new ChangeItem(key, domainScope, level, newValue, isDelete);
    }
}
