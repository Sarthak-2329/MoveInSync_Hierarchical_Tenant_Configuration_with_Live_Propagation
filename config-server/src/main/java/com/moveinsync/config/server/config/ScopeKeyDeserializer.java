package com.moveinsync.config.server.config;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.moveinsync.config.core.model.Scope;

/**
 * Deserializes JSON string object keys back into immutable {@link Scope} records.
 */
public class ScopeKeyDeserializer extends KeyDeserializer {

    @Override
    public Scope deserializeKey(String key, DeserializationContext ctxt) {
        if (key == null || key.isBlank() || "GLOBAL".equalsIgnoreCase(key)) {
            return Scope.global();
        }
        String[] parts = key.split("/");
        if (parts.length >= 2 && "CLIENT".equalsIgnoreCase(parts[0])) {
            return Scope.client(parts[1]);
        }
        if (parts.length >= 3 && "OFFICE".equalsIgnoreCase(parts[0])) {
            return Scope.office(parts[1], parts[2]);
        }
        if (parts.length >= 4 && "SHIFT".equalsIgnoreCase(parts[0])) {
            return Scope.shift(parts[1], parts[2], parts[3]);
        }
        return Scope.global();
    }
}
