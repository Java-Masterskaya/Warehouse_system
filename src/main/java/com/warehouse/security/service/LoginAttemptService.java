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
     * @return время ожидания в секундах, если заблокирован; -1 если доступ разрешен.
     */
    public long checkAndConsume(String username) {
        Bucket bucket = getUsernameBucket(username);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return -1; // Всё ок, попытка списана, проверяем пароль дальше
        }

        long nanos = probe.getNanosToWaitForRefill();
        return (long) Math.ceil((double) nanos / 1_000_000_000L);
    }

    /**
     * Если вход успешный, возвращаем списанный авансом токен назад.
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
