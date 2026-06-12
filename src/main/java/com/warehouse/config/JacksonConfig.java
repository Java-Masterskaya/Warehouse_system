package com.warehouse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация Jackson ObjectMapper.
 * <p>
 * Определяет единый ObjectMapper для всего приложения:
 * <ul>
 *   <li>поддержка Java 8 дат ({@code LocalDateTime} как ISO-строка)</li>
 *   <li>отключён timestamp-формат дат</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}