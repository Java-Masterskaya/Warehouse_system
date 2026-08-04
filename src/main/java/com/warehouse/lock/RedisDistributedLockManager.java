package com.warehouse.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-реализация распределенных блокировок с проверкой владельца при освобождении.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisDistributedLockManager implements DistributedLockManager {

    private static final String KEY_PREFIX = "warehouse:distributed-lock:";
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            """
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    end
                    return 0
                    """,
            Long.class
    );

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public Optional<DistributedLock> tryAcquire(String name, Duration ttl) {
        validateArguments(name, ttl);

        String key = KEY_PREFIX + hash(name);
        String owner = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, owner, ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }

        return Optional.of(new RedisDistributedLock(key, owner));
    }

    private void validateArguments(String name, Duration ttl) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Lock name must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Lock TTL must be positive");
        }
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " algorithm is not available", e);
        }
    }

    private final class RedisDistributedLock implements DistributedLock {

        private final String key;
        private final String owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RedisDistributedLock(String key, String owner) {
            this.key = key;
            this.owner = owner;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            try {
                Long released = redisTemplate.execute(RELEASE_SCRIPT, List.of(key), owner);
                if (!Long.valueOf(1L).equals(released)) {
                    log.debug("Distributed lock was not released because ownership changed or TTL expired");
                }
            } catch (RuntimeException e) {
                log.warn("Failed to release distributed lock; it will expire by TTL", e);
            }
        }
    }
}
