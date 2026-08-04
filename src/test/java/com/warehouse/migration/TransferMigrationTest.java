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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class TransferMigrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TransferMigrationTest.class);

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void testTransferIdNotNullForTransferOperations() {
        log.info("=== Testing transfer_id integrity ===");

        // Используем native query для проверки transfer_id
        List<Object[]> results = entityManager.createNativeQuery(
                "SELECT id, type, transfer_id FROM stock_movements "
                        + "WHERE type IN ('TRANSFER_OUT', 'TRANSFER_IN')"
        ).getResultList();

        log.info("Found {} TRANSFER operations", results.size());

        int nullTransferIdCount = 0;
        for (Object[] row : results) {
            Long id = ((Number) row[0]).longValue();
            String type = (String) row[1];
            Long transferId;

            if (row[2] != null) {
                transferId = ((Number) row[2]).longValue();
            } else {
                transferId = null;
            }

            if (transferId == null) {
                nullTransferIdCount++;
                log.warn("⚠️ NULL transfer_id for movement id={}, type={}", id, type);
            } else {
                log.debug("✅ Movement id={}, type={}, transferId={}", id, type, transferId);
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

        // Проверяем, что для всех TRANSFER записей существует связанная запись
        List<Object[]> results = entityManager.createNativeQuery(
                "SELECT s.id, s.transfer_id, s.type "
                        + "FROM stock_movements s "
                        + "WHERE s.type IN ('TRANSFER_OUT', 'TRANSFER_IN') "
                        + "AND s.transfer_id IS NOT NULL "
                        + "AND NOT EXISTS ("
                        + "    SELECT 1 FROM stock_movements s2 "
                        + "    WHERE s2.id = s.transfer_id"
                        + ")"
        ).getResultList();

        log.info("Found {} broken transfer links", results.size());

        for (Object[] row : results) {
            Long id = ((Number) row[0]).longValue();
            Long transferId = ((Number) row[1]).longValue();
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

        // Проверяем, что все данные скопированы
        Object[] counts = (Object[]) entityManager.createNativeQuery(
                "SELECT "
                        + "  (SELECT COUNT(*) FROM stock_movements_old) AS old_count, "
                        + "  (SELECT COUNT(*) FROM stock_movements) AS new_count"
        ).getSingleResult();

        Long oldCount = ((Number) counts[0]).longValue();
        Long newCount = ((Number) counts[1]).longValue();

        log.info("Records in stock_movements_old: {}", oldCount);
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