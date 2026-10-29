package com.moveinsync.config.server.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.moveinsync.config.core.model.Scope;

import java.io.IOException;

/**
 * Serializes {@link Scope} objects as JSON object keys.
 * Format:
 * - GLOBAL -> "GLOBAL"
 * - CLIENT -> "CLIENT/{clientId}"
 * - OFFICE -> "OFFICE/{clientId}/{officeId}"
 * - SHIFT  -> "SHIFT/{clientId}/{officeId}/{shiftId}"
 */
public class ScopeKeySerializer extends JsonSerializer<Scope> {

    @Override
    public void serialize(Scope scope, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeFieldName(format(scope));
    }

    public static String format(Scope scope) {
        if (scope == null || (scope.clientId() == null && scope.officeId() == null && scope.shiftId() == null)) {
            return "GLOBAL";
        }
        if (scope.officeId() == null && scope.shiftId() == null) {
            return "CLIENT/" + scope.clientId();
        }
        if (scope.shiftId() == null) {
            return "OFFICE/" + scope.clientId() + "/" + scope.officeId();
        }
        return "SHIFT/" + scope.clientId() + "/" + scope.officeId() + "/" + scope.shiftId();
    }
}
