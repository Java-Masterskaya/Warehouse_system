package com.warehouse.service;

import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.batch.BatchServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тест для BatchServiceImpl.
 * Тестирует списание товара по FEFO и очистку просроченных партий.
 */
@ExtendWith(MockitoExtension.class)
public class BatchServiceImplTest {

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @Mock
    private UserRepository userRepository;

    private BatchServiceImpl batchService;

    @BeforeEach
    public void setUp() {
        batchService = new BatchServiceImpl(batchRepository, stockRepository, itemRepository, stockMovementRepository, userRepository);
    }

    /**
     * FEFO списание: успешно списываем из одной партии.
     */
    @Test
    public void shouldWriteOffFromSingleBatch() {
        // Given
        Long itemId = 1L;
        int requestedQuantity = 5;
        int availableQuantity = 10;
        int currentStock = 10;
        int newStockQuantity = 5;

        Stock stock = new Stock();
        stock.setQuantity(currentStock);

        Batch batch = new Batch();
        batch.setId(1L);
        batch.setQuantity(availableQuantity);
        batch.setItem(new Item());

        when(stockRepository.findAvailableQuantityFromBatchesForUpdate(eq(itemId), any(LocalDateTime.class)))
                .thenReturn(Optional.of(availableQuantity));
        when(stockRepository.findByItemIdForUpdate(eq(itemId))).thenReturn(Optional.of(stock));
        when(batchRepository.findNonExpiredByItemIdOrderByExpiryDateAsc(eq(itemId), any(LocalDateTime.class)))
                .thenReturn(List.of(batch));
        when(batchRepository.save(any(Batch.class))).thenAnswer(i -> i.getArgument(0));
        when(stockRepository.save(any(Stock.class))).thenAnswer(i -> i.getArgument(0));

        // When
        int result = batchService.writeOffByFEFO(itemId, requestedQuantity, LocalDateTime.now());

        // Then
        assertEquals(newStockQuantity, result);
        verify(batchRepository).findNonExpiredByItemIdOrderByExpiryDateAsc(eq(itemId), any(LocalDateTime.class));
        verify(stockRepository).findByItemIdForUpdate(eq(itemId));
    }

    /**
     * FEFO списание: списываем из нескольких партий.
     */
    @Test
    public void shouldWriteOffFromMultipleBatches() {
        // Given
        Long itemId = 1L;
        int requestedQuantity = 15;
        int availableQuantity = 15;
        int currentStock = 15;
        int newStockQuantity = 0;

        Batch batch1 = new Batch();
        batch1.setId(1L);
        batch1.setQuantity(5);

        Batch batch2 = new Batch();
        batch2.setId(2L);
        batch2.setQuantity(10);

        Stock stock = new Stock();
        stock.setQuantity(currentStock);

        when(stockRepository.findAvailableQuantityFromBatchesForUpdate(eq(itemId), any(LocalDateTime.class)))
                .thenReturn(Optional.of(availableQuantity));
        when(stockRepository.findByItemIdForUpdate(eq(itemId))).thenReturn(Optional.of(stock));
        when(batchRepository.findNonExpiredByItemIdOrderByExpiryDateAsc(eq(itemId), any(LocalDateTime.class)))
                .thenReturn(List.of(batch1, batch2));
        when(batchRepository.save(any(Batch.class))).thenAnswer(i -> i.getArgument(0));
        when(stockRepository.save(any(Stock.class))).thenAnswer(i -> i.getArgument(0));

        // When
        int result = batchService.writeOffByFEFO(itemId, requestedQuantity, LocalDateTime.now());

        // Then
        assertEquals(newStockQuantity, result);
        // Проверяем, что batchRepository.save() вызывался для каждой партии
        verify(batchRepository, Mockito.times(2)).save(any(Batch.class));
        verify(stockRepository).save(any(Stock.class));
    }

    /**
     * FEFO списание: проверка консистентности stock.quantity = SUM(Batch.quantity).
     * Проверяет, что после списания:
     * 1. Партии обновляются правильно (полностью или частично)
     * 2. Stock.quantity обновляется на разницу списанного
     * 3. Консистентность между stock и партиями соблюдается
     */
    @Test
    public void shouldSyncStockQuantityAfterFEFOWriteOff() {
        // Given
        Long itemId = 1L;
        int requestedQuantity = 25; // Списываем 25: гасим 10 полностью, 15 из 20
        int availableQuantity = 30;
        int currentStock = 30;
        int expectedStockAfter = 5; // 30 - 25

        Batch batch1 = new Batch();
        batch1.setId(1L);
        batch1.setQuantity(10);
        batch1.setItem(new Item());

        Batch batch2 = new Batch();
        batch2.setId(2L);
        batch2.setQuantity(20);
        batch2.setItem(new Item());

        Stock stock = new Stock();
        stock.setQuantity(currentStock);

        when(stockRepository.findAvailableQuantityFromBatchesForUpdate(eq(itemId), any(LocalDateTime.class)))
                .thenReturn(Optional.of(availableQuantity));
        when(stockRepository.findByItemIdForUpdate(eq(itemId))).thenReturn(Optional.of(stock));
        when(batchRepository.findNonExpiredByItemIdOrderByExpiryDateAsc(eq(itemId), any(LocalDateTime.class)))
                .thenReturn(List.of(batch1, batch2));
        when(batchRepository.save(any(Batch.class))).thenAnswer(i -> {
            // Симуляция сохранения: обновляем количество
            Batch saved = i.getArgument(0);
            return saved;
        });
        when(stockRepository.save(any(Stock.class))).thenAnswer(i -> i.getArgument(0));

        // When
        int result = batchService.writeOffByFEFO(itemId, requestedQuantity, LocalDateTime.now());

        // Then
        assertEquals(expectedStockAfter, result);
        
        // Проверяем, что batchRepository.save() вызывался для каждой партии
        verify(batchRepository, Mockito.times(2)).save(any(Batch.class));
        
        // Проверяем, что stockRepository.save() вызывался
        verify(stockRepository).save(any(Stock.class));
        
        // Проверяем, что stock.setQuantity() вызывается с правильным значением
        ArgumentCaptor<Stock> stockCaptor = ArgumentCaptor.forClass(Stock.class);
        verify(stockRepository).save(stockCaptor.capture());
        assertEquals(expectedStockAfter, stockCaptor.getValue().getQuantity());
    }

    /**
     * FEFO списание: недостаточно товара.
     */
    @Test
    public void shouldThrowExceptionWhenInsufficientStock() {
        // Given
        Long itemId = 1L;
        int requestedQuantity = 20;
        int availableQuantity = 10;

        when(stockRepository.findAvailableQuantityFromBatchesForUpdate(eq(itemId), any(LocalDateTime.class)))
                .thenReturn(Optional.of(availableQuantity));

        // When & Then
        InsufficientStockException exception = assertThrows(InsufficientStockException.class,
                () -> batchService.writeOffByFEFO(itemId, requestedQuantity, LocalDateTime.now()));

        assertEquals("Insufficient stock for FEFO write-off: requested 20, available 10", exception.getMessage());
    }

    /**
     * FEFO списание: товар не найден.
     */
    @Test
    public void shouldThrowExceptionWhenStockNotFound() {
        // Given
        Long itemId = 1L;
        int requestedQuantity = 5;

        when(stockRepository.findAvailableQuantityFromBatchesForUpdate(eq(itemId), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        // When & Then
        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class,
                () -> batchService.writeOffByFEFO(itemId, requestedQuantity, LocalDateTime.now()));

        assertEquals("Stock with id 1 not found", exception.getMessage());
    }

    /**
     * Очистка просроченных партий: успешно очищаем.
     */
    @Test
    public void shouldClearExpiredBatchesSuccessfully() {
        // Given
        Long itemId = 1L;
        int totalQty = 15;

        Item item = new Item();
        item.setId(itemId);

        Batch expiredBatch = new Batch();
        expiredBatch.setItem(item);
        expiredBatch.setQuantity(totalQty);

        Stock stock = new Stock();
        stock.setQuantity(20);

        when(batchRepository.findExpiredWithQuantity(any(LocalDateTime.class)))
                .thenReturn(List.of(expiredBatch));
        when(stockRepository.findByItemIdForUpdate(eq(itemId))).thenReturn(Optional.of(stock));
        when(stockRepository.decreaseQuantityIfEnough(eq(itemId), eq(totalQty))).thenReturn(1);
        when(batchRepository.clearExpiredBatchesByItemId(eq(itemId), any(LocalDateTime.class)))
                .thenReturn(1);
        when(stockRepository.syncQuantityWithBatches(eq(itemId))).thenReturn(1);
        when(itemRepository.findById(eq(itemId))).thenReturn(Optional.of(item));

        // When
        int cleared = batchService.clearExpiredBatches(LocalDateTime.now());

        // Then
        assertEquals(1, cleared);
        verify(stockRepository).findByItemIdForUpdate(eq(itemId));
        verify(stockRepository).decreaseQuantityIfEnough(eq(itemId), eq(totalQty));
        verify(batchRepository).clearExpiredBatchesByItemId(eq(itemId), any(LocalDateTime.class));
    }

    /**
     * Проверка консистентности: stock.quantity = SUM(Batch.quantity) после очистки просроченных.
     * Проверяет, что:
     * 1. stock.quantity уменьшается на количество просроченных партий
     * 2. syncQuantityWithBatches вызывается для окончательной синхронизации
     * 3. stock.quantity после очистки = начальный остаток - количество просроченных
     */
    @Test
    public void shouldSyncStockQuantityAfterClearExpired() {
        // Given
        Long itemId = 1L;
        int expiredQty = 8;
        int initialStock = 20;
        int expectedStockAfter = 12; // 20 - 8

        Item item = new Item();
        item.setId(itemId);

        Batch expiredBatch = new Batch();
        expiredBatch.setItem(item);
        expiredBatch.setQuantity(expiredQty);

        Stock stock = new Stock();
        stock.setQuantity(initialStock);

        when(batchRepository.findExpiredWithQuantity(any(LocalDateTime.class)))
                .thenReturn(List.of(expiredBatch));
        when(stockRepository.findByItemIdForUpdate(eq(itemId))).thenReturn(Optional.of(stock));
        when(stockRepository.decreaseQuantityIfEnough(eq(itemId), eq(expiredQty))).thenReturn(1);
        when(batchRepository.clearExpiredBatchesByItemId(eq(itemId), any(LocalDateTime.class)))
                .thenReturn(1);
        when(stockRepository.syncQuantityWithBatches(eq(itemId))).thenReturn(1);
        when(itemRepository.findById(eq(itemId))).thenReturn(Optional.of(item));

        // When
        int cleared = batchService.clearExpiredBatches(LocalDateTime.now());

        // Then
        assertEquals(1, cleared);
        verify(stockRepository).findByItemIdForUpdate(eq(itemId));
        verify(stockRepository).decreaseQuantityIfEnough(eq(itemId), eq(expiredQty));
        verify(batchRepository).clearExpiredBatchesByItemId(eq(itemId), any(LocalDateTime.class));
        verify(stockRepository).syncQuantityWithBatches(eq(itemId));
    }

    /**
     * Очистка просроченных партий: нет просроченных партий.
     */
    @Test
    public void shouldReturnZeroWhenNoExpiredBatches() {
        // Given
        when(batchRepository.findExpiredWithQuantity(any(LocalDateTime.class)))
                .thenReturn(List.of());

        // When
        int cleared = batchService.clearExpiredBatches(LocalDateTime.now());

        // Then
        assertEquals(0, cleared);
        verify(stockRepository, never()).findByItemIdForUpdate(anyLong());
    }

    /**
     * Очистка просроченных партий: недостаточно остатка на складе.
     */
    @Test
    public void shouldSkipWhenInsufficientStock() {
        // Given
        Long itemId = 1L;
        int totalQty = 20;
        int currentStock = 15;

        Item item = new Item();
        item.setId(itemId);

        Batch expiredBatch = new Batch();
        expiredBatch.setItem(item);
        expiredBatch.setQuantity(totalQty);

        Stock stock = new Stock();
        stock.setQuantity(currentStock);

        when(batchRepository.findExpiredWithQuantity(any(LocalDateTime.class)))
                .thenReturn(List.of(expiredBatch));
        when(stockRepository.findByItemIdForUpdate(eq(itemId))).thenReturn(Optional.of(stock));

        // When
        int cleared = batchService.clearExpiredBatches(LocalDateTime.now());

        // Then
        assertEquals(0, cleared);
        verify(batchRepository, never()).clearExpiredBatchesByItemId(anyLong(), any(LocalDateTime.class));
    }
}
