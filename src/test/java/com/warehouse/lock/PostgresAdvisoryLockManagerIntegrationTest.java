package com.warehouse.lock;

import com.warehouse.AbstractIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет advisory-замки на общем Postgres.
 *
 * <p>Свой контейнер здесь не нужен: имена замков уникальны ({@code UUID}), на чужие данные
 * тест не смотрит. Отдельный Postgres добавлял к прогону лишний подъём контейнера.
 * Spring-контекст классу тоже не нужен — берём контейнер напрямую.
 */
@Tag("integration")
class PostgresAdvisoryLockManagerIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = AbstractIntegrationTest.getPostgres();

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
