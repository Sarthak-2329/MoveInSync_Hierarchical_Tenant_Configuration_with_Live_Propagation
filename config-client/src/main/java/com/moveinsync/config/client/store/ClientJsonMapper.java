package com.moveinsync.config.client.store;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moveinsync.config.core.model.OverrideValue;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.Snapshot;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Jackson ObjectMapper tailored for the client library.
 *
 * Configures date/time parsing, scope key serialization, and custom snapshot deserialization.
 */
public final class ClientJsonMapper {

    private static final ObjectMapper MAPPER = createMapper();

    private ClientJsonMapper() {
        // Utility
    }

    public static ObjectMapper get() {
        return MAPPER;
    }

    public static Scope parseScopeKey(String key) {
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

    public static String formatScopeKey(Scope scope) {
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

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        SimpleModule module = new SimpleModule("ClientConfigModule");

        // Scope Key Serializer & Deserializer
        module.addKeySerializer(Scope.class, new JsonSerializer<>() {
            @Override
            public void serialize(Scope scope, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeFieldName(formatScopeKey(scope));
            }
        });
        module.addKeyDeserializer(Scope.class, new KeyDeserializer() {
            @Override
            public Scope deserializeKey(String key, DeserializationContext ctxt) {
                return parseScopeKey(key);
            }
        });

        // Snapshot Deserializer
        module.addDeserializer(Snapshot.class, new JsonDeserializer<>() {
            @Override
            public Snapshot deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                JsonNode node = p.getCodec().readTree(p);
                long version = node.has("version") ? node.get("version").asLong() : 0L;

                Map<String, SettingDefinition> defs = new HashMap<>();
                if (node.has("definitions")) {
                    JsonNode defsNode = node.get("definitions");
                    Iterator<Map.Entry<String, JsonNode>> fields = defsNode.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> entry = fields.next();
                        SettingDefinition def = ctxt.readTreeAsValue(entry.getValue(), SettingDefinition.class);
                        defs.put(entry.getKey(), def);
                    }
                }

                Map<String, Map<Scope, OverrideValue>> overrides = new HashMap<>();
                if (node.has("overridesByKey")) {
                    JsonNode overridesNode = node.get("overridesByKey");
                    Iterator<Map.Entry<String, JsonNode>> keyFields = overridesNode.fields();
                    while (keyFields.hasNext()) {
                        Map.Entry<String, JsonNode> keyEntry = keyFields.next();
                        String settingKey = keyEntry.getKey();
                        Map<Scope, OverrideValue> scopeMap = new HashMap<>();

                        Iterator<Map.Entry<String, JsonNode>> scopeFields = keyEntry.getValue().fields();
                        while (scopeFields.hasNext()) {
                            Map.Entry<String, JsonNode> scopeEntry = scopeFields.next();
                            Scope scope = parseScopeKey(scopeEntry.getKey());
                            OverrideValue ov = ctxt.readTreeAsValue(scopeEntry.getValue(), OverrideValue.class);
                            scopeMap.put(scope, ov);
                        }
                        overrides.put(settingKey, scopeMap);
                    }
                }

                return new Snapshot(version, defs, overrides);
            }
        });

        mapper.registerModule(module);
        return mapper;
    }
}
