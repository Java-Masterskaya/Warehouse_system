package com.warehouse.service;

import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.stock.StockServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты для StockServiceImpl: списание и приход товара.
 * Проверяют: успешное списание, приход, недостаточный остаток, ошибки сущности.
 */
@ExtendWith(MockitoExtension.class)
class StockServiceImplTest {

    private static final Long ITEM_ID = 1L;
    private static final Long NON_EXISTENT_ITEM_ID = 99L;

    private static final int INITIAL_STOCK_QUANTITY = 10;
    private static final int LOW_STOCK_QUANTITY = 3;
    private static final int WRITE_OFF_AMOUNT = 5;
    private static final int EXCESSIVE_AMOUNT = 15;
    private static final int EXPECTED_STOCK_AFTER_WRITE_OFF = 5;
    private static final int RECEIVE_AMOUNT = 7;
    private static final int EXPECTED_STOCK_AFTER_RECEIVE = 17;

    @Mock
    private StockRepository stockRepository;

    @InjectMocks
    private StockServiceImpl stockService;

    /**
     * Списание товара с достаточным остатком успешно обновляет количество.
     */
    @Test
    void writeOffStockSuccess() {
        // Arrange
        Item item = createItem(ITEM_ID);
        Stock stock = createStock(item, INITIAL_STOCK_QUANTITY);

        when(stockRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        int result = stockService.writeOffStock(ITEM_ID, WRITE_OFF_AMOUNT);

        // Assert
        assertEquals(EXPECTED_STOCK_AFTER_WRITE_OFF, result);
        assertEquals(EXPECTED_STOCK_AFTER_WRITE_OFF, stock.getQuantity());

        verify(stockRepository).save(argThat(savedStock ->
                savedStock.getQuantity() == EXPECTED_STOCK_AFTER_WRITE_OFF
        ));
    }

    /**
     * Списание точного количества остатка приводит к нулевому остатку.
     */
    @Test
    void exactQuantityReturnsZero() {
        // Arrange - списываем ровно столько, сколько есть
        Item item = createItem(ITEM_ID);
        Stock stock = createStock(item, WRITE_OFF_AMOUNT);

        when(stockRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        int result = stockService.writeOffStock(ITEM_ID, WRITE_OFF_AMOUNT);

        // Assert
        assertEquals(0, result);
        assertEquals(0, stock.getQuantity());
    }

    /**
     * Списание для несуществующего остатка выбрасывает EntityNotFoundException.
     */
    @Test
    void stockNotFoundThrowsEntityNotFoundException() {
        // Arrange
        when(stockRepository.findByItemId(NON_EXISTENT_ITEM_ID)).thenReturn(Optional.empty());

        // Act & Assert
        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () -> {
            stockService.writeOffStock(NON_EXISTENT_ITEM_ID, WRITE_OFF_AMOUNT);
        });

        // Проверяем сообщение исключения
        String message = ex.getMessage();
        assertTrue(message.contains("Stock"), "Сообщение должно содержать название сущности");
        assertTrue(message.contains(String.valueOf(NON_EXISTENT_ITEM_ID)),
                "Сообщение должно содержать ID товара");
        assertTrue(message.contains("not found"),
                "Сообщение должно содержать 'not found'");

        // Сохранение не вызывалось
        verify(stockRepository, never()).save(any());
    }

    /**
     * Списание при недостаточном остатке выбрасывает InsufficientStockException.
     */
    @Test
    void insufficientStockThrowsInsufficientStockException() {
        // Arrange
        Item item = createItem(ITEM_ID);
        Stock stock = createStock(item, LOW_STOCK_QUANTITY);

        when(stockRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(stock));

        // Act & Assert
        InsufficientStockException ex = assertThrows(InsufficientStockException.class, () -> {
            stockService.writeOffStock(ITEM_ID, EXCESSIVE_AMOUNT);
        });

        // Проверяем сообщение исключения
        String message = ex.getMessage();
        assertTrue(message.contains("Insufficient stock"),
                "Сообщение должно содержать 'Insufficient stock'");
        assertTrue(message.contains(String.valueOf(ITEM_ID)),
                "Сообщение должно содержать ID товара");
        assertTrue(message.contains(String.valueOf(EXCESSIVE_AMOUNT)),
                "Сообщение должно содержать запрошенное количество");
        assertTrue(message.contains(String.valueOf(LOW_STOCK_QUANTITY)),
                "Сообщение должно содержать доступное количество");

        // Остаток не должен измениться
        assertEquals(LOW_STOCK_QUANTITY, stock.getQuantity());
        verify(stockRepository, never()).save(any());
    }

    /**
     * Приход товара успешно увеличивает остаток.
     */
    @Test
    void receiveStockSuccess() {
        // Arrange
        Item item = createItem(ITEM_ID);
        Stock stock = createStock(item, INITIAL_STOCK_QUANTITY);

        when(stockRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        int result = stockService.receiveStock(ITEM_ID, RECEIVE_AMOUNT);

        // Assert
        assertEquals(EXPECTED_STOCK_AFTER_RECEIVE, result);
        assertEquals(EXPECTED_STOCK_AFTER_RECEIVE, stock.getQuantity());

        verify(stockRepository).save(argThat(savedStock ->
                savedStock.getQuantity() == EXPECTED_STOCK_AFTER_RECEIVE
        ));
    }

    /**
     * Приход нуля не меняет остаток.
     */
    @Test
    void receiveStockZeroQuantityReturnsSameStock() {
        // Arrange
        Item item = createItem(ITEM_ID);
        Stock stock = createStock(item, INITIAL_STOCK_QUANTITY);

        when(stockRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        int result = stockService.receiveStock(ITEM_ID, 0);

        // Assert
        assertEquals(INITIAL_STOCK_QUANTITY, result);
        assertEquals(INITIAL_STOCK_QUANTITY, stock.getQuantity());
    }

    /**
     * Приход для несуществующего остатка выбрасывает EntityNotFoundException.
     */
    @Test
    void receiveStockNotFoundThrowsEntityNotFoundException() {
        // Arrange
        when(stockRepository.findByItemId(NON_EXISTENT_ITEM_ID)).thenReturn(Optional.empty());

        // Act & Assert
        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () -> {
            stockService.receiveStock(NON_EXISTENT_ITEM_ID, RECEIVE_AMOUNT);
        });

        // Проверяем сообщение исключения
        String message = ex.getMessage();
        assertTrue(message.contains("Stock"), "Сообщение должно содержать название сущности");
        assertTrue(message.contains(String.valueOf(NON_EXISTENT_ITEM_ID)),
                "Сообщение должно содержать ID товара");
        assertTrue(message.contains("not found"),
                "Сообщение должно содержать 'not found'");

        verify(stockRepository, never()).save(any());
    }

    /**
     * Приход отрицательного количества уменьшает остаток (интерпретируется как списание).
     */
    @Test
    void receiveStockNegativeQuantityDecreasesStock() {
        // Arrange
        Item item = createItem(ITEM_ID);
        Stock stock = createStock(item, INITIAL_STOCK_QUANTITY);

        when(stockRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        int result = stockService.receiveStock(ITEM_ID, -3);

        // Assert
        assertEquals(7, result); // 10 - 3 = 7
        assertEquals(7, stock.getQuantity());
    }

    /**
     * Приход очень большого количества корректно обрабатывается.
     */
    @Test
    void receiveStockLargeQuantity() {
        // Arrange
        Item item = createItem(ITEM_ID);
        Stock stock = createStock(item, INITIAL_STOCK_QUANTITY);

        when(stockRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        int largeAmount = Integer.MAX_VALUE - INITIAL_STOCK_QUANTITY;
        int result = stockService.receiveStock(ITEM_ID, largeAmount);

        // Assert
        assertEquals(Integer.MAX_VALUE, result);
        assertEquals(Integer.MAX_VALUE, stock.getQuantity());
    }

    /**
     * Приход максимального целого значения вызывает переполнение (edge case).
     */
    @Test
    void receiveStockMaxIntegerCausesOverflow() {
        // Arrange
        Item item = createItem(ITEM_ID);
        Stock stock = createStock(item, INITIAL_STOCK_QUANTITY);

        when(stockRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        int result = stockService.receiveStock(ITEM_ID, Integer.MAX_VALUE);

        // Assert
        // Ожидаем переполнение: 10 + Integer.MAX_VALUE = Integer.MIN_VALUE + 9
        assertEquals(Integer.MIN_VALUE + 9, result);
    }

    /**
     * Вспомогательный метод для создания Item.
     *
     * @param itemId ID товара
     * @return созданный объект Item
     */
    private Item createItem(Long itemId) {
        Item item = new Item();
        item.setId(itemId);
        return item;
    }

    /**
     * Вспомогательный метод для создания Stock.
     *
     * @param item     товар
     * @param quantity количество
     * @return созданный объект Stock
     */
    private Stock createStock(Item item, int quantity) {
        Stock stock = new Stock();
        stock.setItem(item);
        stock.setQuantity(quantity);
        return stock;
    }
}