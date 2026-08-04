package com.warehouse.service;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.batch.BatchCleanupActor;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.batch.BatchService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты конкурентного обновления остатков.
 * <p>
 * Важно: сам тест не должен выполняться в одной общей транзакции,
 * иначе worker-потоки могут не увидеть созданные Item/Stock.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BatchServiceConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final int TIMEOUT_SECONDS = 10;

    @Autowired
    private BatchService batchService;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    /**
     * Проверяет, что параллельные списания одного товара не приводят к oversell.
     * <p>
     * Несколько потоков одновременно пытаются списать товар с одного и того же остатка.
     * Успешных списаний должно быть ровно столько, сколько позволяет текущий остаток,
     * а лишняя операция должна получить ошибку недостаточного количества товара.
     */
    @Test
    void parallelWriteOffsDoNotOversell() throws Exception {
        int initialQuantity = 400;
        int writeOffQuantity = 20;
        int expectedSuccessfulWriteOffs = initialQuantity / writeOffQuantity;
        int totalAttempts = expectedSuccessfulWriteOffs + 1;

        Item item = createItem();
        Warehouse warehouse = defaultWarehouse();
        createStock(item, warehouse, initialQuantity);

        ExecutorService executor = Executors.newFixedThreadPool(totalAttempts);
        CountDownLatch ready = new CountDownLatch(totalAttempts);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Callable<Boolean>> tasks = IntStream.range(0, totalAttempts)
                    .mapToObj(index -> (Callable<Boolean>) () ->
                            writeOffAfterStart(
                                    ready,
                                    start,
                                    item.getId(),
                                    warehouse.getId(),
                                    writeOffQuantity
                            ))
                    .toList();

            List<Future<Boolean>> futures = submit(tasks, executor);

            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            start.countDown();

            long successfulWriteOffs = countSuccessful(futures);
            int quantityAfter = stockRepository
                    .findByItemIdAndWarehouseId(item.getId(), warehouse.getId())
                    .orElseThrow()
                    .getQuantity();

            assertThat(successfulWriteOffs).isEqualTo(expectedSuccessfulWriteOffs);
            assertThat(quantityAfter).isZero();
            assertStockMatchesBatchTotal(item.getId(), warehouse.getId());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Проверяет, что параллельные приходы одного товара не теряют обновления.
     * <p>
     * Несколько потоков одновременно увеличивают один и тот же остаток.
     * Итоговое количество должно быть равно сумме всех успешных приходов,
     * без перезаписи результата одного потока другим.
     */
    @Test
    void parallelReceiptsDoNotLoseUpdates() throws Exception {
        int initialQuantity = 0;
        int threadCount = 20;
        int receiptQuantity = 5;
        int expectedQuantity = threadCount * receiptQuantity;

        Item item = createItem();
        Warehouse warehouse = defaultWarehouse();
        createStock(item, warehouse, initialQuantity);
        LocalDateTime expiryDate = LocalDateTime.now().plusDays(30);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Callable<Integer>> tasks = IntStream.range(0, threadCount)
                    .mapToObj(index -> (Callable<Integer>) () ->
                            receiveAfterStart(
                                    ready,
                                    start,
                                    item,
                                    warehouse,
                                    receiptQuantity,
                                    expiryDate
                            ))
                    .toList();

            List<Future<Integer>> futures = submit(tasks, executor);

            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            start.countDown();

            waitAll(futures);

            int quantityAfter = stockRepository
                    .findByItemIdAndWarehouseId(item.getId(), warehouse.getId())
                    .orElseThrow()
                    .getQuantity();

            assertThat(quantityAfter).isEqualTo(expectedQuantity);
            assertStockMatchesBatchTotal(item.getId(), warehouse.getId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cleanupReleasesFirstScopeLockBeforeProcessingSecondScope() throws Exception {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Warehouse warehouse = defaultWarehouse();
        Item firstItem = createItem();
        Item secondItem = createItem();
        Stock firstStock = createStock(firstItem, warehouse, 5);
        Stock secondStock = createStock(secondItem, warehouse, 7);
        expireBatches(firstItem, warehouse, now.minusDays(2));
        expireBatches(secondItem, warehouse, now.minusDays(1));
        ensureBatchCleanupActor();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Integer> cleanup = null;
        try (Connection blockedScopeConnection = dataSource.getConnection()) {
            blockedScopeConnection.setAutoCommit(false);
            lockStock(blockedScopeConnection, secondStock.getId(), false);

            cleanup = executor.submit(() -> batchService.clearExpiredBatches(now));

            Awaitility.await()
                    .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> expiredMovementCount(firstItem.getId()) == 1);

            assertThat(cleanup.isDone()).isFalse();
            assertStockCanBeLocked(firstStock.getId());
            blockedScopeConnection.rollback();

            assertThat(cleanup.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isGreaterThanOrEqualTo(2);
            assertThat(stockQuantity(firstItem, warehouse)).isZero();
            assertThat(stockQuantity(secondItem, warehouse)).isZero();
            assertThat(expiredMovementCount(firstItem.getId())).isEqualTo(1);
            assertThat(expiredMovementCount(secondItem.getId())).isEqualTo(1);
            assertStockMatchesBatchTotal(firstItem.getId(), warehouse.getId());
            assertStockMatchesBatchTotal(secondItem.getId(), warehouse.getId());
        } finally {
            if (cleanup != null && !cleanup.isDone()) {
                cleanup.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    private boolean writeOffAfterStart(CountDownLatch ready,
                                       CountDownLatch start,
                                       Long itemId,
                                       Long warehouseId,
                                       int quantity) throws InterruptedException {
        ready.countDown();
        start.await();

        try {
            batchService.writeOffByFEFO(itemId, warehouseId, quantity, LocalDateTime.now());
            return true;
        } catch (InsufficientStockException ex) {
            return false;
        }
    }

    private int receiveAfterStart(CountDownLatch ready,
                                  CountDownLatch start,
                                  Item item,
                                  Warehouse warehouse,
                                  int quantity,
                                  LocalDateTime expiryDate) throws InterruptedException {
        ready.countDown();
        start.await();

        return batchService.createBatchAndIncreaseStock(
                item,
                warehouse,
                quantity,
                expiryDate
        ).getQuantity();
    }

    private long countSuccessful(List<Future<Boolean>> futures)
            throws InterruptedException, ExecutionException, TimeoutException {
        long successful = 0;

        for (Future<Boolean> future : futures) {
            if (future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                successful++;
            }
        }

        return successful;
    }

    private void waitAll(List<Future<Integer>> futures)
            throws InterruptedException, ExecutionException, TimeoutException {
        for (Future<Integer> future : futures) {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private <T> List<Future<T>> submit(List<Callable<T>> tasks, ExecutorService executor) {
        return tasks.stream()
                .map(executor::submit)
                .toList();
    }

    private Item createItem() {
        Category category = categoryRepository.findByNameIgnoreCase("Test")
                .orElseGet(() -> categoryRepository.save(
                        Category.builder()
                                .name("Test")
                                .build()
                ));

        Item item = new Item();
        item.setSku("SKU-CONC-" + UUID.randomUUID());
        item.setName("Concurrent item");
        item.setCategory(category);
        item.setMinStock(5);
        item.setActive(true);
        item.setBarcode("ITEM-TEST-CONC-" + UUID.randomUUID());

        return itemRepository.saveAndFlush(item);
    }

    private Stock createStock(Item item, Warehouse warehouse, int quantity) {
        Stock stock = new Stock();
        stock.setItem(item);
        stock.setWarehouse(warehouse);
        stock.setQuantity(quantity);

        Stock savedStock = stockRepository.saveAndFlush(stock);
        batchRepository.saveAndFlush(Batch.builder()
                .item(item)
                .warehouse(savedStock.getWarehouse())
                .quantity(quantity)
                .expiryDate(LocalDateTime.now().plusDays(30))
                .build());
        return savedStock;
    }

    private void expireBatches(Item item, Warehouse warehouse, LocalDateTime expiryDate) {
        List<Batch> batches = batchRepository.findByItemIdAndWarehouseIdOrderByExpiryDateAsc(
                item.getId(),
                warehouse.getId()
        );
        batches.forEach(batch -> batch.setExpiryDate(expiryDate));
        batchRepository.saveAllAndFlush(batches);
    }

    private void ensureBatchCleanupActor() {
        userRepository.findByUsername(BatchCleanupActor.USERNAME)
                .orElseGet(() -> userRepository.saveAndFlush(User.builder()
                        .username(BatchCleanupActor.USERNAME)
                        .password("!disabled-system-actor!")
                        .role(Role.ROLE_USER)
                        .active(false)
                        .build()));
    }

    private long expiredMovementCount(Long itemId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM stock_movements
                        WHERE item_id = ?
                          AND type = 'EXPIRED'
                        """,
                Long.class,
                itemId
        );
    }

    private int stockQuantity(Item item, Warehouse warehouse) {
        return stockRepository.findByItemIdAndWarehouseId(item.getId(), warehouse.getId())
                .orElseThrow()
                .getQuantity();
    }

    private void assertStockCanBeLocked(Long stockId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            lockStock(connection, stockId, true);
            connection.rollback();
        }
    }

    private void lockStock(Connection connection, Long stockId, boolean failFast) throws Exception {
        String lockClause = " FOR UPDATE";
        if (failFast) {
            lockClause += " NOWAIT";
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM stock WHERE id = ?" + lockClause
        )) {
            statement.setLong(1, stockId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    private void assertStockMatchesBatchTotal(Long itemId, Long warehouseId) {
        int stockQuantity = stockRepository.findByItemIdAndWarehouseId(itemId, warehouseId)
                .orElseThrow()
                .getQuantity();
        int batchQuantity = batchRepository
                .findByItemIdAndWarehouseIdOrderByExpiryDateAsc(itemId, warehouseId)
                .stream()
                .mapToInt(Batch::getQuantity)
                .sum();

        assertThat(stockQuantity).isEqualTo(batchQuantity);
    }
}
