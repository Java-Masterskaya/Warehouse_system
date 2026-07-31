package com.warehouse.service;

import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.ExpiredBatchScope;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.batch.BatchCleanupActor;
import com.warehouse.service.batch.BatchServiceImpl;
import com.warehouse.service.batch.ExpiredBatchCleanupWorker;
import com.warehouse.service.reservation.StockAvailabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchServiceImplTest {

    private static final long ITEM_ID = 10L;
    private static final long SOURCE_WAREHOUSE_ID = 20L;
    private static final long DESTINATION_WAREHOUSE_ID = 30L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 25, 12, 0);

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private StockAvailabilityService availabilityService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ExpiredBatchCleanupWorker expiredBatchCleanupWorker;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache itemCache;

    @Mock
    private ExpiredBatchScope sourceScope;

    @Mock
    private ExpiredBatchScope destinationScope;

    private BatchServiceImpl batchService;
    private Item item;
    private Warehouse sourceWarehouse;
    private Warehouse destinationWarehouse;

    @BeforeEach
    void setUp() {
        batchService = new BatchServiceImpl(
                batchRepository,
                stockRepository,
                availabilityService,
                userRepository,
                expiredBatchCleanupWorker,
                cacheManager
        );
        item = Item.builder().id(ITEM_ID).build();
        sourceWarehouse = Warehouse.builder().id(SOURCE_WAREHOUSE_ID).name("Source").build();
        destinationWarehouse = Warehouse.builder()
                .id(DESTINATION_WAREHOUSE_ID)
                .name("Destination")
                .build();
    }

    @Test
    void shouldCreateBatchForWarehouseAndIncreaseItsStock() {
        Stock stock = stock(sourceWarehouse, 7);
        LocalDateTime expiryDate = LocalDateTime.now().plusYears(1);
        when(stockRepository.findByItemIdAndWarehouseIdForUpdate(ITEM_ID, SOURCE_WAREHOUSE_ID))
                .thenReturn(Optional.of(stock));
        when(batchRepository.save(any(Batch.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Batch result = batchService.createBatchAndIncreaseStock(
                item,
                sourceWarehouse,
                5,
                expiryDate
        );

        ArgumentCaptor<Batch> batchCaptor = ArgumentCaptor.forClass(Batch.class);
        verify(stockRepository).createEmptyStockIfAbsent(ITEM_ID, SOURCE_WAREHOUSE_ID);
        verify(batchRepository).save(batchCaptor.capture());
        verify(stockRepository).save(stock);
        Batch savedBatch = batchCaptor.getValue();
        assertAll(
                () -> assertSame(savedBatch, result),
                () -> assertSame(item, savedBatch.getItem()),
                () -> assertSame(sourceWarehouse, savedBatch.getWarehouse()),
                () -> assertEquals(5, savedBatch.getQuantity()),
                () -> assertEquals(expiryDate, savedBatch.getExpiryDate()),
                () -> assertEquals(12, stock.getQuantity())
        );
    }

    @Test
    void shouldWriteOffByFefoOnlyAtRequestedWarehouse() {
        Stock stock = stock(sourceWarehouse, 20);
        Batch first = batch(sourceWarehouse, 5, NOW.plusDays(2));
        Batch second = batch(sourceWarehouse, 10, NOW.plusDays(5));
        List<Batch> sourceBatches = List.of(first, second);
        when(stockRepository.findByItemIdAndWarehouseIdForUpdate(ITEM_ID, SOURCE_WAREHOUSE_ID))
                .thenReturn(Optional.of(stock));
        when(batchRepository.findNonExpiredByItemAndWarehouseOrderByExpiryDateAscForUpdate(
                ITEM_ID,
                SOURCE_WAREHOUSE_ID,
                NOW
        )).thenReturn(sourceBatches);
        when(availabilityService.getAvailable(stock)).thenReturn(12);

        int remainingStock = batchService.writeOffByFEFO(
                ITEM_ID,
                SOURCE_WAREHOUSE_ID,
                12,
                NOW
        );

        assertAll(
                () -> assertEquals(8, remainingStock),
                () -> assertEquals(0, first.getQuantity()),
                () -> assertEquals(3, second.getQuantity()),
                () -> assertEquals(8, stock.getQuantity())
        );
        verify(batchRepository).saveAll(sourceBatches);
        verify(stockRepository).save(stock);
        verify(batchRepository, never())
                .findNonExpiredByItemAndWarehouseOrderByExpiryDateAscForUpdate(
                        ITEM_ID,
                        DESTINATION_WAREHOUSE_ID,
                        NOW
            );
    }

    @Test
    void shouldRejectRegularWriteOffWhenFreeAvailabilityIsInsufficient() {
        Stock stock = stock(sourceWarehouse, 20);
        Batch batch = batch(sourceWarehouse, 20, NOW.plusDays(2));
        when(stockRepository.findByItemIdAndWarehouseIdForUpdate(ITEM_ID, SOURCE_WAREHOUSE_ID))
                .thenReturn(Optional.of(stock));
        when(batchRepository.findNonExpiredByItemAndWarehouseOrderByExpiryDateAscForUpdate(
                ITEM_ID,
                SOURCE_WAREHOUSE_ID,
                NOW
        )).thenReturn(List.of(batch));
        when(availabilityService.getAvailable(stock)).thenReturn(6);

        assertThrows(
                InsufficientStockException.class,
                () -> batchService.writeOffByFEFO(ITEM_ID, SOURCE_WAREHOUSE_ID, 7, NOW)
        );

        assertAll(
                () -> assertEquals(20, batch.getQuantity()),
                () -> assertEquals(20, stock.getQuantity())
        );
        verify(batchRepository, never()).saveAll(any());
        verify(stockRepository, never()).save(any(Stock.class));
    }

    @Test
    void shouldWriteOffReservedQuantityWithoutSubtractingReservationAgain() {
        Stock stock = stock(sourceWarehouse, 10);
        Batch batch = batch(sourceWarehouse, 10, NOW.plusDays(2));
        List<Batch> batches = List.of(batch);
        when(stockRepository.findByItemIdAndWarehouseIdForUpdate(ITEM_ID, SOURCE_WAREHOUSE_ID))
                .thenReturn(Optional.of(stock));
        when(batchRepository.findNonExpiredByItemAndWarehouseOrderByExpiryDateAscForUpdate(
                ITEM_ID,
                SOURCE_WAREHOUSE_ID,
                NOW
        )).thenReturn(batches);

        int remainingStock = batchService.writeOffReservedByFEFO(
                ITEM_ID,
                SOURCE_WAREHOUSE_ID,
                7,
                NOW
        );

        assertAll(
                () -> assertEquals(3, remainingStock),
                () -> assertEquals(3, batch.getQuantity()),
                () -> assertEquals(3, stock.getQuantity())
        );
        verify(availabilityService, never()).getAvailable(any(Stock.class));
        verify(batchRepository).saveAll(batches);
        verify(stockRepository).save(stock);
    }

    @Test
    void shouldClearExpiredBatchesThroughIndependentScopeWorkers() {
        User actor = User.builder().id(40L).build();
        when(sourceScope.getItemId()).thenReturn(ITEM_ID);
        when(sourceScope.getWarehouseId()).thenReturn(SOURCE_WAREHOUSE_ID);
        when(destinationScope.getItemId()).thenReturn(ITEM_ID);
        when(destinationScope.getWarehouseId()).thenReturn(DESTINATION_WAREHOUSE_ID);
        when(userRepository.findByUsername(BatchCleanupActor.USERNAME))
                .thenReturn(Optional.of(actor));
        when(cacheManager.getCache("item")).thenReturn(itemCache);
        when(batchRepository.findExpiredScopesWithQuantity(NOW))
                .thenReturn(List.of(sourceScope, destinationScope));
        when(expiredBatchCleanupWorker.clearScope(
                ITEM_ID,
                SOURCE_WAREHOUSE_ID,
                actor.getId(),
                NOW
        )).thenReturn(2);
        when(expiredBatchCleanupWorker.clearScope(
                ITEM_ID,
                DESTINATION_WAREHOUSE_ID,
                actor.getId(),
                NOW
        )).thenReturn(1);

        int cleared = batchService.clearExpiredBatches(NOW);

        assertEquals(3, cleared);
        verify(expiredBatchCleanupWorker).clearScope(
                ITEM_ID,
                SOURCE_WAREHOUSE_ID,
                actor.getId(),
                NOW
        );
        verify(expiredBatchCleanupWorker).clearScope(
                ITEM_ID,
                DESTINATION_WAREHOUSE_ID,
                actor.getId(),
                NOW
        );
        verify(itemCache, times(2)).evict(ITEM_ID);
    }

    @Test
    void shouldContinueCleanupWhenOneScopeFails() {
        User actor = User.builder().id(40L).build();
        when(sourceScope.getItemId()).thenReturn(ITEM_ID);
        when(sourceScope.getWarehouseId()).thenReturn(SOURCE_WAREHOUSE_ID);
        when(destinationScope.getItemId()).thenReturn(ITEM_ID);
        when(destinationScope.getWarehouseId()).thenReturn(DESTINATION_WAREHOUSE_ID);
        when(userRepository.findByUsername(BatchCleanupActor.USERNAME))
                .thenReturn(Optional.of(actor));
        when(cacheManager.getCache("item")).thenReturn(itemCache);
        when(batchRepository.findExpiredScopesWithQuantity(NOW))
                .thenReturn(List.of(sourceScope, destinationScope));
        when(expiredBatchCleanupWorker.clearScope(
                ITEM_ID,
                SOURCE_WAREHOUSE_ID,
                actor.getId(),
                NOW
        )).thenThrow(new IllegalStateException("simulated scope failure"));
        when(expiredBatchCleanupWorker.clearScope(
                ITEM_ID,
                DESTINATION_WAREHOUSE_ID,
                actor.getId(),
                NOW
        )).thenReturn(1);

        int cleared = batchService.clearExpiredBatches(NOW);

        assertEquals(1, cleared);
        verify(expiredBatchCleanupWorker).clearScope(
                ITEM_ID,
                SOURCE_WAREHOUSE_ID,
                actor.getId(),
                NOW
        );
        verify(expiredBatchCleanupWorker).clearScope(
                ITEM_ID,
                DESTINATION_WAREHOUSE_ID,
                actor.getId(),
                NOW
        );
        verify(itemCache).evict(ITEM_ID);
    }

    @Test
    void shouldRunCleanupOrchestratorWithoutSharedTransaction() throws NoSuchMethodException {
        Method method = BatchServiceImpl.class.getMethod("clearExpiredBatches", LocalDateTime.class);

        Transactional annotation = method.getAnnotation(Transactional.class);

        assertNotNull(annotation);
        assertEquals(Propagation.NOT_SUPPORTED, annotation.propagation());
    }

    private Stock stock(Warehouse warehouse, int quantity) {
        return Stock.builder()
                .item(item)
                .warehouse(warehouse)
                .quantity(quantity)
                .build();
    }

    private Batch batch(Warehouse warehouse, int quantity, LocalDateTime expiryDate) {
        return Batch.builder()
                .item(item)
                .warehouse(warehouse)
                .quantity(quantity)
                .expiryDate(expiryDate)
                .build();
    }

}
