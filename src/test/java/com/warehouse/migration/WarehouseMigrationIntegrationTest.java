package com.warehouse.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the real upgrade path from the single-warehouse schema at V18 to the multi-warehouse schema.
 */
@Testcontainers
class WarehouseMigrationIntegrationTest {

    private static final long DEFAULT_WAREHOUSE_ID = 1L;
    private static final int LEGACY_QUANTITY = 37;
    private static final long LEGACY_VERSION = 4L;

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migrationPreservesLegacyStockMovementAndReservation() throws Exception {
        migrateToV18();
        LegacyData legacy = seedLegacyData();

        long stockCountBefore = countRows("stock");
        long movementCountBefore = countRows("stock_movements");
        long reserveCountBefore = countRows("reserves");

        migrateToLatest();

        assertDefaultWarehouse();
        assertLegacyStock(legacy);
        assertLegacyMovement(legacy);
        assertLegacyReservation(legacy);
        assertThat(countRows("stock")).isEqualTo(stockCountBefore);
        assertThat(countRows("stock_movements")).isEqualTo(movementCountBefore);
        assertThat(countRows("reserves")).isEqualTo(reserveCountBefore);
        assertRequiredWarehouseColumns();
        assertWarehouseIdsMustBeExplicit(legacy);

        long secondWarehouseId = insertWarehouse("Secondary Warehouse");
        assertThat(secondWarehouseId).isEqualTo(2L);
        assertThatThrownBy(() -> insertWarehouse("secondary warehouse"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_warehouses_name_ci");

        long secondStockId = insertStock(legacy.itemId(), secondWarehouseId, 11);
        assertThat(secondStockId).isPositive();
        assertThat(sumStock(legacy.itemId())).isEqualTo(LEGACY_QUANTITY + 11L);
        assertTransferMovementTypesAccepted(legacy, secondWarehouseId);

        assertThatThrownBy(() -> insertStock(legacy.itemId(), secondWarehouseId, 5))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_stock_item_warehouse");
        assertThat(countStockRows(legacy.itemId())).isEqualTo(2L);
    }

    private void migrateToV18() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("18"))
                .load()
                .migrate();
    }

    private void migrateToLatest() {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("23");
    }

    private LegacyData seedLegacyData() throws SQLException {
        try (Connection connection = connection()) {
            long userId = queryLong(connection, "SELECT id FROM users WHERE username = 'admin'");
            long itemId = insertReturningId(connection, """
                    INSERT INTO items (sku, name, category, min_stock, is_active, price, cost)
                    VALUES ('MIGRATION-SKU', 'Migration Item', 'Migration', 5, TRUE, 15.00, 9.00)
                    RETURNING id
                    """);

            Timestamp stockUpdatedAt = Timestamp.valueOf("2026-07-01 10:15:30");
            long stockId = insertReturningId(connection, """
                    INSERT INTO stock (item_id, quantity, updated_at, version)
                    VALUES (?, ?, ?, ?)
                    RETURNING id
                    """, itemId, LEGACY_QUANTITY, stockUpdatedAt, LEGACY_VERSION);

            Timestamp movementCreatedAt = Timestamp.valueOf("2026-07-01 10:16:00");
            long movementId = insertReturningId(connection, """
                    INSERT INTO stock_movements (item_id, user_id, type, quantity, created_at)
                    VALUES (?, ?, 'RECEIVE', ?, ?)
                    RETURNING id
                    """, itemId, userId, LEGACY_QUANTITY, movementCreatedAt);

            long reserveId = insertReturningId(connection, """
                    INSERT INTO reserves (stock_id, user_id, quantity, status, created_at, expired_at)
                    VALUES (?, ?, 5, 'ACTIVE', TIMESTAMP '2026-07-01 10:17:00',
                            TIMESTAMP '2099-07-01 10:17:00')
                    RETURNING id
                    """, stockId, userId);

            return new LegacyData(
                    itemId,
                    stockId,
                    movementId,
                    reserveId,
                    userId,
                    stockUpdatedAt,
                    movementCreatedAt
            );
        }
    }

    private void assertDefaultWarehouse() throws SQLException {
        try (Connection connection = connection()) {
            assertThat(queryLong(connection, "SELECT COUNT(*) FROM warehouses")).isEqualTo(1L);
            assertThat(queryLong(connection,
                    "SELECT COUNT(*) FROM warehouses WHERE is_default = TRUE")).isEqualTo(1L);

            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT name, is_default
                    FROM warehouses
                    WHERE id = ?
                    """)) {
                statement.setLong(1, DEFAULT_WAREHOUSE_ID);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("name")).isEqualTo("Default Warehouse");
                    assertThat(result.getBoolean("is_default")).isTrue();
                    assertThat(result.next()).isFalse();
                }
            }
        }
    }

    private void assertLegacyStock(LegacyData legacy) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT item_id, quantity, updated_at, version, warehouse_id
                     FROM stock
                     WHERE id = ?
                     """)) {
            statement.setLong(1, legacy.stockId());
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("item_id")).isEqualTo(legacy.itemId());
                assertThat(result.getInt("quantity")).isEqualTo(LEGACY_QUANTITY);
                assertThat(result.getTimestamp("updated_at")).isEqualTo(legacy.stockUpdatedAt());
                assertThat(result.getLong("version")).isEqualTo(LEGACY_VERSION);
                assertThat(result.getLong("warehouse_id")).isEqualTo(DEFAULT_WAREHOUSE_ID);
                assertThat(result.next()).isFalse();
            }
        }
    }

    private void assertLegacyMovement(LegacyData legacy) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT item_id, user_id, type, quantity, created_at, warehouse_id, transfer_id
                     FROM stock_movements
                     WHERE id = ?
                     """)) {
            statement.setLong(1, legacy.movementId());
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("item_id")).isEqualTo(legacy.itemId());
                assertThat(result.getLong("user_id")).isEqualTo(legacy.userId());
                assertThat(result.getString("type")).isEqualTo("RECEIVE");
                assertThat(result.getInt("quantity")).isEqualTo(LEGACY_QUANTITY);
                assertThat(result.getTimestamp("created_at")).isEqualTo(legacy.movementCreatedAt());
                assertThat(result.getLong("warehouse_id")).isEqualTo(DEFAULT_WAREHOUSE_ID);
                assertThat(result.getObject("transfer_id")).isNull();
                assertThat(result.next()).isFalse();
            }
        }
    }

    private void assertLegacyReservation(LegacyData legacy) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT stock_id, user_id, quantity, status
                     FROM reserves
                     WHERE id = ?
                     """)) {
            statement.setLong(1, legacy.reserveId());
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("stock_id")).isEqualTo(legacy.stockId());
                assertThat(result.getLong("user_id")).isEqualTo(legacy.userId());
                assertThat(result.getInt("quantity")).isEqualTo(5);
                assertThat(result.getString("status")).isEqualTo("ACTIVE");
                assertThat(result.next()).isFalse();
            }
        }
    }

    private void assertRequiredWarehouseColumns() throws SQLException {
        try (Connection connection = connection()) {
            assertThat(queryString(connection, """
                    SELECT is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'stock' AND column_name = 'warehouse_id'
                    """)).isEqualTo("NO");
            assertThat(queryString(connection, """
                    SELECT column_default
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'stock' AND column_name = 'warehouse_id'
                    """)).isNull();
            assertThat(queryString(connection, """
                    SELECT is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'stock_movements'
                      AND column_name = 'warehouse_id'
                    """)).isEqualTo("NO");
            assertThat(queryString(connection, """
                    SELECT column_default
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'stock_movements'
                      AND column_name = 'warehouse_id'
                    """)).isNull();
            assertThat(queryString(connection, """
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'stock_movements'
                      AND column_name = 'transfer_id'
                    """)).isEqualTo("uuid");
        }
    }

    private void assertTransferMovementTypesAccepted(LegacyData legacy, long secondWarehouseId)
            throws SQLException {
        UUID transferId = UUID.fromString("5ac37a55-bd71-4021-acd3-10ae1c5ea68c");

        assertThat(insertMovement(
                legacy.itemId(),
                legacy.userId(),
                "TRANSFER_OUT",
                DEFAULT_WAREHOUSE_ID,
                transferId
        )).isPositive();
        assertThat(insertMovement(
                legacy.itemId(),
                legacy.userId(),
                "TRANSFER_IN",
                secondWarehouseId,
                transferId
        )).isPositive();
    }

    private long insertMovement(
            long itemId,
            long userId,
            String type,
            long warehouseId,
            UUID transferId
    ) throws SQLException {
        try (Connection connection = connection()) {
            return insertReturningId(connection, """
                    INSERT INTO stock_movements
                        (item_id, user_id, type, quantity, created_at, warehouse_id, transfer_id)
                    VALUES (?, ?, ?, 1, NOW(), ?, ?)
                    RETURNING id
                    """, itemId, userId, type, warehouseId, transferId);
        }
    }

    private void assertWarehouseIdsMustBeExplicit(LegacyData legacy) {
        assertThatThrownBy(() -> insertStockWithoutWarehouse(legacy.itemId()))
                .isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo("23502"));
        assertThatThrownBy(() -> insertMovementWithoutWarehouse(legacy.itemId(), legacy.userId()))
                .isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo("23502"));
    }

    private void insertStockWithoutWarehouse(long itemId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO stock (item_id, quantity, updated_at, version)
                     VALUES (?, 1, NOW(), 0)
                     """)) {
            statement.setLong(1, itemId);
            statement.executeUpdate();
        }
    }

    private void insertMovementWithoutWarehouse(long itemId, long userId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO stock_movements (item_id, user_id, type, quantity, created_at)
                     VALUES (?, ?, 'RECEIVE', 1, NOW())
                     """)) {
            statement.setLong(1, itemId);
            statement.setLong(2, userId);
            statement.executeUpdate();
        }
    }

    private long insertWarehouse(String name) throws SQLException {
        try (Connection connection = connection()) {
            return insertReturningId(connection, """
                    INSERT INTO warehouses (name, is_default)
                    VALUES (?, FALSE)
                    RETURNING id
                    """, name);
        }
    }

    private long insertStock(long itemId, long warehouseId, int quantity) throws SQLException {
        try (Connection connection = connection()) {
            return insertReturningId(connection, """
                    INSERT INTO stock (item_id, warehouse_id, quantity, updated_at, version)
                    VALUES (?, ?, ?, NOW(), 0)
                    RETURNING id
                    """, itemId, warehouseId, quantity);
        }
    }

    private long sumStock(long itemId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT SUM(quantity)
                     FROM stock
                     WHERE item_id = ?
                     """)) {
            statement.setLong(1, itemId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private long countStockRows(long itemId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM stock
                     WHERE item_id = ?
                     """)) {
            statement.setLong(1, itemId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private long countRows(String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (Connection connection = connection()) {
            return queryLong(connection, sql);
        }
    }

    private long insertReturningId(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private long queryLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private String queryString(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
    }

    private record LegacyData(
            long itemId,
            long stockId,
            long movementId,
            long reserveId,
            long userId,
            Timestamp stockUpdatedAt,
            Timestamp movementCreatedAt
    ) {
    }
}
