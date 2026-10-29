package com.moveinsync.config.server.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moveinsync.config.core.model.Scope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson JSON serialization configuration.
 *
 * Configures:
 * 1. JavaTimeModule for java.time types (Instant, Duration, LocalTime).
 * 2. Custom Scope key serializer & deserializer for Map<Scope, OverrideValue>.
 * 3. Lenient deserialization (FAIL_ON_UNKNOWN_PROPERTIES = false).
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        SimpleModule scopeModule = new SimpleModule("ScopeKeyModule");
        scopeModule.addKeySerializer(Scope.class, new ScopeKeySerializer());
        scopeModule.addKeyDeserializer(Scope.class, new ScopeKeyDeserializer());
        mapper.registerModule(scopeModule);

        return mapper;
    }
}
