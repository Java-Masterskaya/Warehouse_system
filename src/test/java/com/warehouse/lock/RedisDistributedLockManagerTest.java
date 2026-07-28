package com.warehouse.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Redis distributed lock manager unit tests")
class RedisDistributedLockManagerTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisDistributedLockManager lockManager;

    @BeforeEach
    void setUp() {
        lockManager = new RedisDistributedLockManager(redisTemplate);
    }

    @Test
    @DisplayName("Should acquire an opaque Redis lock with TTL and release it by owner")
    void shouldAcquireOpaqueLockAndReleaseByOwner() {
        String logicalName = "refresh-rotation:raw-sensitive-value";
        Duration ttl = Duration.ofSeconds(30);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), eq(ttl))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any()))
                .thenReturn(1L);

        Optional<DistributedLock> acquired = lockManager.tryAcquire(logicalName, ttl);

        assertThat(acquired).isPresent();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ownerCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(keyCaptor.capture(), ownerCaptor.capture(), eq(ttl));
        assertThat(keyCaptor.getValue())
                .startsWith("warehouse:distributed-lock:")
                .doesNotContain(logicalName)
                .doesNotContain("raw-sensitive-value");
        assertThat(ownerCaptor.getValue()).isNotBlank();

        acquired.orElseThrow().close();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(keyCaptor.getValue())),
                eq(ownerCaptor.getValue())
        );
    }

    @Test
    @DisplayName("Should not release Redis state when lock was not acquired")
    void shouldReturnEmptyWhenLockIsAlreadyHeld() {
        Duration ttl = Duration.ofSeconds(30);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), eq(ttl))).thenReturn(false);

        Optional<DistributedLock> acquired = lockManager.tryAcquire("held-lock", ttl);

        assertThat(acquired).isEmpty();
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(List.class), any());
    }

    @Test
    @DisplayName("Should reject non-positive TTL")
    void shouldRejectNonPositiveTtl() {
        assertThatThrownBy(() -> lockManager.tryAcquire("lock", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TTL");
    }
}
