package com.warehouse.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages PostgreSQL session-scoped advisory locks.
 *
 * <p>Each acquired lock owns a dedicated JDBC connection. The database session and
 * its advisory lock remain alive until the returned handle is closed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostgresAdvisoryLockManager {

    private static final String TRY_LOCK_SQL =
            "SELECT pg_try_advisory_lock(hashtextextended(?, 0))";
    private static final String UNLOCK_SQL =
            "SELECT pg_advisory_unlock(hashtextextended(?, 0))";

    private final DataSource dataSource;

    /**
     * Attempts to acquire a named lock without waiting.
     *
     * @param name logical lock name
     * @return an owner handle or an empty result when another session owns the lock
     */
    public Optional<DistributedLock> tryAcquire(String name) {
        validateName(name);

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (!tryAcquire(connection, name)) {
                closeConnection(connection);
                return Optional.empty();
            }
            return Optional.of(new PostgresAdvisoryLock(name, connection));
        } catch (SQLException e) {
            releaseAndCloseAfterAcquisitionFailure(name, connection);
            throw new IllegalStateException("Failed to acquire PostgreSQL advisory lock: " + name, e);
        }
    }

    private boolean tryAcquire(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("PostgreSQL advisory lock query returned no result");
                }
                return resultSet.getBoolean(1);
            }
        }
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Lock name must not be blank");
        }
    }

    private void releaseAndCloseAfterAcquisitionFailure(String name, Connection connection) {
        if (connection == null) {
            return;
        }
        releaseAndClose(name, connection);
    }

    private void releaseAndClose(String name, Connection connection) {
        try {
            unlock(name, connection);
        } catch (SQLException e) {
            log.warn("Failed to explicitly release PostgreSQL advisory lock: {}", name, e);
            abortConnection(connection);
        } finally {
            closeConnection(connection);
        }
    }

    private void unlock(String name, Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("PostgreSQL advisory unlock query returned no result");
                }
                if (!resultSet.getBoolean(1)) {
                    log.debug("PostgreSQL advisory lock was not owned by this session: {}", name);
                }
            }
        }
    }

    private void abortConnection(Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException e) {
            log.warn("Failed to abort PostgreSQL advisory lock connection", e);
        }
    }

    private void closeConnection(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Failed to close PostgreSQL advisory lock connection", e);
        }
    }

    private final class PostgresAdvisoryLock implements DistributedLock {

        private final String name;
        private final Connection connection;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PostgresAdvisoryLock(String name, Connection connection) {
            this.name = name;
            this.connection = connection;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            releaseAndClose(name, connection);
        }
    }
}
