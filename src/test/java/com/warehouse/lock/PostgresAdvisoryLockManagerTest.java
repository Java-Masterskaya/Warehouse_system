package com.warehouse.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresAdvisoryLockManagerTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement acquireStatement;

    @Mock
    private PreparedStatement unlockStatement;

    @Mock
    private ResultSet acquireResult;

    private PostgresAdvisoryLockManager lockManager;

    @BeforeEach
    void setUp() {
        lockManager = new PostgresAdvisoryLockManager(dataSource);
    }

    @Test
    void shouldAbortAndCloseConnectionWhenExplicitUnlockFails() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString()))
                .thenReturn(acquireStatement, unlockStatement);
        when(acquireStatement.executeQuery()).thenReturn(acquireResult);
        when(acquireResult.next()).thenReturn(true);
        when(acquireResult.getBoolean(1)).thenReturn(true);
        when(unlockStatement.executeQuery()).thenThrow(new SQLException("unlock failed"));
        DistributedLock lock = lockManager.tryAcquire("test-lock").orElseThrow();

        lock.close();
        lock.close();

        verify(connection, times(1)).abort(any(Executor.class));
        verify(connection, times(1)).close();
    }
}
