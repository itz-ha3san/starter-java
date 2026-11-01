package edu.softwarelab.starter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit Jackson 2 mapper for components that use com.fasterxml.jackson APIs.
 * Spring Boot 4 does not automatically provide the legacy Jackson 2 mapper
 * expected by S4ResultConsumer.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }
}
