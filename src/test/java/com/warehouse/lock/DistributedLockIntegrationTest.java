package com.warehouse.lock;

import com.warehouse.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@DisplayName("Distributed lock integration tests")
class DistributedLockIntegrationTest extends AbstractIntegrationTest {

    private static final Duration EXPIRED_OWNER_TTL = Duration.ofMillis(100);
    private static final Duration ACTIVE_OWNER_TTL = Duration.ofSeconds(5);

    @Autowired
    private DistributedLockManager lockManager;

    @Test
    @DisplayName("Expired owner must not release a lock acquired by a new owner")
    void expiredOwnerMustNotReleaseNewOwnerLock() {
        String lockName = "ownership-safe-release-" + UUID.randomUUID();
        DistributedLock expiredOwner = lockManager.tryAcquire(lockName, EXPIRED_OWNER_TTL).orElseThrow();
        AtomicReference<DistributedLock> activeOwnerRef = new AtomicReference<>();

        await()
                .pollInterval(Duration.ofMillis(20))
                .atMost(Duration.ofSeconds(5))
                .until(() -> acquireInto(lockName, activeOwnerRef));

        DistributedLock activeOwner = activeOwnerRef.get();
        try {
            expiredOwner.close();

            assertThat(lockManager.tryAcquire(lockName, ACTIVE_OWNER_TTL)).isEmpty();
        } finally {
            activeOwner.close();
        }

        try (DistributedLock reacquired = lockManager.tryAcquire(lockName, ACTIVE_OWNER_TTL).orElseThrow()) {
            assertThat(reacquired).isNotNull();
        }
    }

    private boolean acquireInto(String lockName, AtomicReference<DistributedLock> target) {
        Optional<DistributedLock> acquired = lockManager.tryAcquire(lockName, ACTIVE_OWNER_TTL);
        acquired.ifPresent(target::set);
        return acquired.isPresent();
    }
}
