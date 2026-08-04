package com.warehouse.lock;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
class PostgresAdvisoryLockManagerIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void shouldKeepLockForSessionAndReleaseItIdempotently() {
        DataSource dataSource = dataSource();
        PostgresAdvisoryLockManager firstManager = new PostgresAdvisoryLockManager(dataSource);
        PostgresAdvisoryLockManager secondManager = new PostgresAdvisoryLockManager(dataSource);
        String lockName = "postgres-advisory-lock-" + UUID.randomUUID();
        DistributedLock firstOwner = firstManager.tryAcquire(lockName).orElseThrow();

        try {
            assertThat(secondManager.tryAcquire(lockName)).isEmpty();
        } finally {
            firstOwner.close();
        }
        firstOwner.close();

        try (DistributedLock secondOwner = secondManager.tryAcquire(lockName).orElseThrow()) {
            assertThat(secondOwner).isNotNull();
        }
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }
}
