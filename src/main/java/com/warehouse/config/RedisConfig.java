package com.warehouse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Конфигурация кэширования на основе Redis.
 * <p>
 * Настраивает {@link RedisCacheManager} с раздельными конфигурациями для каждого кэша:
 * <ul>
 *   <li><b>categories</b> — {@code List<String>}, TTL 10 минут</li>
 *   <li><b>item</b> — {@link ItemDetailsResponse}, TTL 5 минут</li>
 * </ul>
 * <p>
 * Для сериализации значений используется {@link Jackson2JsonRedisSerializer} с явно заданным типом.
 * Ключи сериализуются как строки, null-значения не кэшируются.
 * <p>
 * ObjectMapper поставляется бином из {@link JacksonConfig}.
 *
 * @see EnableCaching
 * @see RedisCacheManager
 * @see JacksonConfig
 */
@Configuration
@EnableCaching
public class RedisConfig {

    private static final int CATEGORIES_TTL_MINUTES = 10;
    private static final int ITEM_TTL_MINUTES = 5;

    /**
     * Создаёт и настраивает {@link RedisCacheManager} с двумя именованными кэшами.
     * <p>
     * Базовая конфигурация задаёт строковые ключи и отключает кэширование null.
     * Каждый кэш переопределяет TTL и сериализатор значений:
     * <ul>
     *   <li>{@code categories} — {@link Jackson2JsonRedisSerializer} для {@code List}, TTL 10 минут</li>
     *   <li>{@code item} — {@link Jackson2JsonRedisSerializer} для {@link ItemDetailsResponse}, TTL 5 минут</li>
     * </ul>
     *
     * @param connectionFactory фабрика соединений с Redis,
     *                          автоматически создаётся Spring Boot из {@code spring.data.redis.*}
     * @param objectMapper      кастомный ObjectMapper из {@link JacksonConfig}
     * @return настроенный менеджер кэша
     */
    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper
    ) {
        RedisCacheConfiguration baseConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()
                        )
                )
                .disableCachingNullValues();

        RedisCacheConfiguration categoriesConfig = baseConfig
                .entryTtl(Duration.ofMinutes(CATEGORIES_TTL_MINUTES))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(
                                        objectMapper,
                                        List.class
                                )
                        )
                );

        RedisCacheConfiguration itemConfig = baseConfig
                .entryTtl(Duration.ofMinutes(ITEM_TTL_MINUTES))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(
                                        objectMapper,
                                        ItemDetailsResponse.class
                                )
                        )
                );

        Map<String, RedisCacheConfiguration> configs = new HashMap<>();
        configs.put("categories", categoriesConfig);
        configs.put("item", itemConfig);

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(baseConfig)
                .withInitialCacheConfigurations(configs)
                .build();
    }
}