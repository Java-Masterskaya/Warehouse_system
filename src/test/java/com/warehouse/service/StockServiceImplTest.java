package com.warehouse.service;

import com.warehouse.entity.Stock;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.reservation.StockAvailabilityService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тест для StockServiceImpl.
 * Тестирует операции списания товара и обработку недостаточного остатка.
 */
@ExtendWith(MockitoExtension.class)
class StockServiceImplTest {

    private static final Long ITEM_ID = 1L;
    private static final Long NON_EXISTENT_ITEM_ID = 99L;

    private static final int LOW_STOCK_QUANTITY = 3;
    private static final int WRITE_OFF_AMOUNT = 5;
    private static final int EXCESSIVE_AMOUNT = 15;
    private static final int EXPECTED_STOCK_AFTER_WRITE_OFF = 5;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private StockAvailabilityService availabilityService;

    @InjectMocks
    private StockServiceImpl stockService;

    /**
     * Тесты для метода writeOffStock.
     */
    @Test
    void writeOffStockSuccess() {
        // Arrange
        when(stockRepository.decreaseQuantityIfEnough(ITEM_ID, WRITE_OFF_AMOUNT)).thenReturn(1);
        when(stockRepository.findQuantityByItemId(ITEM_ID)).thenReturn(Optional.of(EXPECTED_STOCK_AFTER_WRITE_OFF));
        Stock stock = new Stock();
        when(stockRepository.findByItemIdForUpdate(ITEM_ID)).thenReturn(Optional.of(stock));
        when(availabilityService.getAvailable(ITEM_ID)).thenReturn(EXCESSIVE_AMOUNT);

        // Act
        int result = stockService.writeOffStock(ITEM_ID, WRITE_OFF_AMOUNT);

        // Assert
        assertEquals(EXPECTED_STOCK_AFTER_WRITE_OFF, result);
        verify(stockRepository).decreaseQuantityIfEnough(ITEM_ID, WRITE_OFF_AMOUNT);
    }

    /**
     * Списание ровно столько, сколько есть, возвращает 0.
     */
    @Test
    void exactQuantityReturnsZero() {
        // Arrange
        when(stockRepository.decreaseQuantityIfEnough(ITEM_ID, WRITE_OFF_AMOUNT)).thenReturn(1);
        when(stockRepository.findQuantityByItemId(ITEM_ID)).thenReturn(Optional.of(0));
        Stock stock = new Stock();
        when(stockRepository.findByItemIdForUpdate(ITEM_ID)).thenReturn(Optional.of(stock));
        when(availabilityService.getAvailable(ITEM_ID)).thenReturn(EXCESSIVE_AMOUNT);

        // Act
        int result = stockService.writeOffStock(ITEM_ID, WRITE_OFF_AMOUNT);

        // Assert
        assertEquals(0, result);
    }

    /**
     * Товар не найден выбрасывает EntityNotFoundException.
     */
    @Test
    void stockNotFoundThrowsEntityNotFoundException() {
        // Arrange
        when(stockRepository.decreaseQuantityIfEnough(NON_EXISTENT_ITEM_ID, WRITE_OFF_AMOUNT)).thenReturn(0);
        when(stockRepository.findQuantityByItemId(NON_EXISTENT_ITEM_ID)).thenReturn(Optional.empty());
        Stock stock = new Stock();
        when(stockRepository.findByItemIdForUpdate(NON_EXISTENT_ITEM_ID)).thenReturn(Optional.of(stock));
        when(availabilityService.getAvailable(NON_EXISTENT_ITEM_ID)).thenReturn(EXCESSIVE_AMOUNT);

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
        verify(stockRepository, never()).findByItemId(NON_EXISTENT_ITEM_ID);
    }

    /**
     * Недостаточный остаток выбрасывает InsufficientStockException.
     */
    @Test
    void insufficientStockThrowsInsufficientStockException() {
        // Arrange
        when(stockRepository.decreaseQuantityIfEnough(ITEM_ID, EXCESSIVE_AMOUNT)).thenReturn(0);
        when(stockRepository.findQuantityByItemId(ITEM_ID)).thenReturn(Optional.of(LOW_STOCK_QUANTITY));
        Stock stock = new Stock();
        when(stockRepository.findByItemIdForUpdate(ITEM_ID)).thenReturn(Optional.of(stock));
        when(availabilityService.getAvailable(ITEM_ID)).thenReturn(EXCESSIVE_AMOUNT);

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
        verify(stockRepository, never()).findByItemId(ITEM_ID);
    }

    /**
     * Недостаточный доступный остаток выбрасывает InsufficientStockException.
     */
    @Test
    void insufficientStockAvailableThrowsInsufficientStockException() {
        // Arrange
        Stock stock = new Stock();
        when(stockRepository.findByItemIdForUpdate(ITEM_ID)).thenReturn(Optional.of(stock));
        when(availabilityService.getAvailable(ITEM_ID)).thenReturn(LOW_STOCK_QUANTITY);

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
        verify(stockRepository, never()).findByItemId(ITEM_ID);
    }
}
