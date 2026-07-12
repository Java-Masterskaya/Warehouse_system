package com.warehouse.security.service;

import com.warehouse.security.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Сервис управления блокировками по Username
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final ProxyManager<byte[]> proxyManager;
    private final RateLimitProperties properties;

    /**
     * Проверяет, заблокирован ли пользователь. Списывает 1 попытку авансом.
     * @param username Имя пользователя для авторизации.
     * @return время ожидания в секундах, если заблокирован; -1 если доступ разрешен.
     */
    public long checkAndConsume(String username) {
        Bucket bucket = getUsernameBucket(username);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return -1; // Всё ок, попытка списана, проверяем пароль дальше
        }

        long nanos = probe.getNanosToWaitForRefill();
        long nanosPerSecond = 1_000_000_000L;
        return (long) Math.ceil((double) nanos / nanosPerSecond);
    }

    /**
     * Если вход успешный, возвращаем списанный авансом токен назад.
     * @param username Имя пользователя для авторизации.
     */
    public void registerSuccess(String username) {
        Bucket bucket = getUsernameBucket(username);
        // Возвращаем токен, но не выше максимума
        bucket.addTokens(1);
    }

    private Bucket getUsernameBucket(String username) {
        RateLimitProperties.BandwidthConfig config = properties.login().username();
        BucketConfiguration bucketConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(config.capacity(), Refill.greedy(config.capacity(), config.duration())))
                .build();
        return proxyManager.builder().build(("rl:login:user:" + username).getBytes(), bucketConfig);
    }
}
