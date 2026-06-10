package com.warehouse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Конфигурация кэширования на основе Redis.
 * <p>
 * Настраивает {@link RedisCacheManager} с JSON-сериализацией через Jackson.
 * Определяет два кэша с разными TTL:
 * <ul>
 *   <li><b>categories</b> — список активных категорий товаров, TTL 10 минут</li>
 *   <li><b>item</b> — карточка товара по ID, TTL 5 минут</li>
 * </ul>
 * <p>
 * Ключи кэша сериализуются как строки, значения — как JSON с поддержкой
 * Java 8 дат ({@link java.time.LocalDateTime}) и деактивированным timestamp-форматом.
 * Null-значения не кэшируются.
 *
 * @see EnableCaching
 * @see RedisCacheManager
 */
@Configuration
@EnableCaching
public class RedisConfig {

    private static final int CATEGORIES_TTL_MINUTES = 10;
    private static final int ITEM_TTL_MINUTES = 5;

    /**
     * Создаёт и настраивает {@link RedisCacheManager}.
     * <p>
     * Использует {@link GenericJackson2JsonRedisSerializer} для сериализации значений,
     * что позволяет хранить в кэше объекты любых типов без дополнительной настройки.
     * Для ключей используется {@link StringRedisSerializer}.
     *
     * @param connectionFactory фабрика соединений с Redis, автоматически создаётся Spring Boot
     *                          из настроек {@code spring.data.redis.*}
     * @return настроенный менеджер кэша с TTL 10 мин для категорий и 5 мин для карточек товаров
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer)
                )
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> configs = new HashMap<>();

        configs.put(
                "categories",
                defaultConfig.entryTtl(Duration.ofMinutes(CATEGORIES_TTL_MINUTES))
        );

        configs.put(
                "item",
                defaultConfig.entryTtl(Duration.ofMinutes(ITEM_TTL_MINUTES))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(configs)
                .build();
    }
}