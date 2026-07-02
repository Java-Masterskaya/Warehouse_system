package com.warehouse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.context.config.annotation.RefreshScope;
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

@Configuration
@EnableCaching
@RefreshScope
public class RedisConfig {

    @Value("${redis.cache.categories-ttl-minutes:10}")
    private int categoriesTtlMinutes;

    @Value("${redis.cache.item-ttl-minutes:5}")
    private int itemTtlMinutes;

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
                .entryTtl(Duration.ofMinutes(categoriesTtlMinutes))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(
                                        objectMapper,
                                        List.class
                                )
                        )
                );

        RedisCacheConfiguration itemConfig = baseConfig
                .entryTtl(Duration.ofMinutes(itemTtlMinutes))
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