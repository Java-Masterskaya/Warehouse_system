package com.warehouse.service;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.batch.BatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
