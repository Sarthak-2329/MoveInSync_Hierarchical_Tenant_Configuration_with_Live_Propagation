package com.moveinsync.config.server.dto;

import com.moveinsync.config.core.model.Scope;

public record ScopeDto(String clientId, String officeId, String shiftId) {

    public Scope toDomain() {
        return new Scope(clientId, officeId, shiftId);
    }

    public static ScopeDto fromDomain(Scope scope) {
        if (scope == null) {
            return new ScopeDto(null, null, null);
        }
        return new ScopeDto(scope.clientId(), scope.officeId(), scope.shiftId());
    }
}
