package com.warehouse.security.config;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Slf4j
@Configuration
public class RateLimitConfig {

    /**
     * Создаём бин ProxyManager с использованием Lettuce (стандартного
     * драйвера Redis в Spring). Он управляет созданием и синхронизацией
     * корзин (buckets) прямо в Redis.
     */
    @Bean
    public ProxyManager<byte[]> proxyManager(RedisConnectionFactory connectionFactory) {
        // Извлекаем нативный клиент Lettuce из фабрики Spring Boot
        LettuceConnectionFactory lettuceFactory = (LettuceConnectionFactory) connectionFactory;
        RedisClient redisClient = (RedisClient) lettuceFactory.getNativeClient();

        if (redisClient == null) {
            log.error("Ошибка: Lettuce RedisClient не был инициализирован!");
            throw new IllegalStateException("Lettuce RedisClient не инициализирован!");
        }

        StatefulRedisConnection<byte[], byte[]> connection = redisClient
                .connect(RedisCodec.of(new ByteArrayCodec(), new ByteArrayCodec()));
        return LettuceBasedProxyManager.builderFor(connection).build();
    }
}
