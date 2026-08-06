package com.warehouse.migration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.repository.StockMovementRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate; // Добавлено

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class TransferMigrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TransferMigrationTest.class);

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void testTransferIdNotNullForTransferOperations() {
        log.info("=== Testing transfer_id integrity ===");

        List<Object[]> results = entityManager.createNativeQuery(
                "SELECT id, type, transfer_id FROM stock_movements "
                        + "WHERE type IN ('TRANSFER_OUT', 'TRANSFER_IN')"
        ).getResultList();

        int nullTransferIdCount = 0;
        for (Object[] row : results) {
            Long id = ((Number) row[0]).longValue();
            String type = (String) row[1];

            String transferId = row[2] != null ? row[2].toString() : null;

            if (transferId == null) {
                nullTransferIdCount++;
                log.warn("⚠️ NULL transfer_id for movement id={}, type={}", id, type);
            }
        }

        log.info("Total TRANSFER operations: {}", results.size());
        log.info("Operations with NULL transfer_id: {}", nullTransferIdCount);

        assertThat(nullTransferIdCount)
                .as("Found %d TRANSFER operations with NULL transfer_id", nullTransferIdCount)
                .isZero();

        log.info("✅ All TRANSFER operations have valid transfer_id");
    }

    @Test
    void testTransferLinksAreValid() {
        log.info("=== Testing transfer links validity ===");

        List<Object[]> results = entityManager.createNativeQuery(
                "SELECT s.id, s.transfer_id, s.type "
                        + "FROM stock_movements s "
                        + "WHERE s.type IN ('TRANSFER_OUT', 'TRANSFER_IN') "
                        + "AND s.transfer_id IS NOT NULL "
                        + "AND NOT EXISTS ("
                        + "    SELECT 1 FROM stock_movements s2 "
                        + "    WHERE CAST(s2.id AS text) = CAST(s.transfer_id AS text)"
                        + ")"
        ).getResultList();

        log.info("Found {} broken transfer links", results.size());

        for (Object[] row : results) {
            Long id = ((Number) row[0]).longValue();

            String transferId = row[1] != null ? row[1].toString() : "null";
            String type = (String) row[2];

            log.warn("⚠️ Broken link: movement id={}, type={}, transferId={} not found",
                    id, type, transferId);
        }

        assertThat(results)
                .as("Found %d broken transfer links", results.size())
                .isEmpty();

        log.info("✅ All transfer links are valid");
    }

    @Test
    void testAllDataCopiedCorrectly() {
        log.info("=== Testing data copy integrity ===");

        Integer tableExists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'stock_movements_archive'",
                Integer.class
        );

        if (tableExists == 0) {
            log.warn("⚠️ stock_movements_archive not found. Skipping integrity check.");
            return;
        }

        Object[] counts = (Object[]) entityManager.createNativeQuery(
                "SELECT "
                        + "  (SELECT COUNT(*) FROM stock_movements_archive) AS old_count, "
                        + "  (SELECT COUNT(*) FROM stock_movements) AS new_count"
        ).getSingleResult();

        Long oldCount = ((Number) counts[0]).longValue();
        Long newCount = ((Number) counts[1]).longValue();

        log.info("Records in stock_movements_archive: {}", oldCount);
        log.info("Records in stock_movements: {}", newCount);

        assertThat(newCount)
                .as("Records count mismatch: old=%d, new=%d", oldCount, newCount)
                .isEqualTo(oldCount);

        log.info("✅ All data copied correctly");
    }

    @Test
    void testNoNullWarehouseId() {
        log.info("=== Testing warehouse_id integrity ===");

        // Проверяем, что нет NULL warehouse_id
        List<Long> nullWarehouseIds = entityManager.createNativeQuery(
                "SELECT id FROM stock_movements WHERE warehouse_id IS NULL"
        ).getResultList();

        log.info("Records with NULL warehouse_id: {}", nullWarehouseIds.size());

        if (!nullWarehouseIds.isEmpty()) {
            for (Long id : nullWarehouseIds) {
                log.warn("⚠️ NULL warehouse_id for movement id={}", id);
            }
        }

        assertThat(nullWarehouseIds)
                .as("Found %d records with NULL warehouse_id", nullWarehouseIds.size())
                .isEmpty();

        log.info("✅ All records have warehouse_id");
    }
}