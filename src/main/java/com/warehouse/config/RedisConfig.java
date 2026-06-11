package com.warehouse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Конфигурация кэширования на основе Redis.
 * <p>
 * Настраивает {@link RedisCacheManager} с двумя стратегиями сериализации:
 * <ul>
 *   <li><b>categories</b> — {@link GenericJackson2JsonRedisSerializer} для {@code List<String>}, TTL 10 минут</li>
 *   <li><b>item</b> — {@link Jackson2JsonRedisSerializer} с явным типом {@link ItemDetailsResponse}, TTL 5 минут</li>
 * </ul>
 * <p>
 * Разделение сериализаторов позволяет избежать добавления метаинформации {@code @class}
 * в JSON для record-типов и гарантирует корректную десериализацию без {@code ClassCastException}.
 * <p>
 * Ключи кэша сериализуются как строки, null-значения не кэшируются.
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
     * Создаёт кастомный {@link ObjectMapper} с поддержкой Java 8 дат.
     * <p>
     * Используется во всех сериализаторах Redis для единообразного формата JSON.
     * Отключён timestamp-формат дат — {@link java.time.LocalDateTime} сериализуется как ISO-строка.
     *
     * @return настроенный ObjectMapper с модулем {@link JavaTimeModule}
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * Создаёт и настраивает {@link RedisCacheManager}.
     * <p>
     * Определяет два именованных кэша с индивидуальными сериализаторами и TTL:
     * <ul>
     *   <li>{@code categories} — универсальный сериализатор для коллекций, TTL 10 минут</li>
     *   <li>{@code item} — типизированный сериализатор для {@link ItemDetailsResponse}, TTL 5 минут</li>
     * </ul>
     * <p>
     * Кэш по умолчанию использует {@link GenericJackson2JsonRedisSerializer}
     * и может быть переопределён для новых кэшей через {@code withInitialCacheConfigurations}.
     *
     * @param connectionFactory фабрика соединений с Redis, автоматически создаётся Spring Boot
     *                          из настроек {@code spring.data.redis.*}
     * @param objectMapper      кастомный ObjectMapper с поддержкой Java 8 дат
     * @return настроенный менеджер кэша
     */
    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper
    ) {
        GenericJackson2JsonRedisSerializer genericSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        Jackson2JsonRedisSerializer<ItemDetailsResponse> itemSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, ItemDetailsResponse.class);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(genericSerializer)
                )
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> configs = new HashMap<>();

        configs.put("categories", defaultConfig.entryTtl(Duration.ofMinutes(CATEGORIES_TTL_MINUTES)));

        configs.put("item", defaultConfig
                .entryTtl(Duration.ofMinutes(ITEM_TTL_MINUTES))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(itemSerializer)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(configs)
                .build();
    }
}