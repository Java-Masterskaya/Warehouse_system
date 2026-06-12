package com.warehouse.service.movement;

import com.warehouse.dto.request.movement.CreateStockMovementRequest;
import com.warehouse.entity.Item;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.service.stock.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Unit-тест сервиса — Spring-контекст не поднимается, репозитории заменены моками
@ExtendWith(MockitoExtension.class)
class StockMovementServiceImplTest {

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private StockService stockService;

    @Mock
    private StockMovementRepository stockMovementRepository;

    private StockMovementService stockMovementService;

    @Captor
    private ArgumentCaptor<StockMovement> stockMovementCaptor;

    @BeforeEach
    void setUp() {
        stockMovementService = new StockMovementServiceImpl(null, stockService, itemRepository, stockMovementRepository);
    }

    @Test
    void registerReceiptSuccess() {
        // 1. Подготовка данных
        Long itemId = 1L;
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");

        Item item = new Item();
        item.setId(itemId);
        item.setActive(true);

        CreateStockMovementRequest request = new CreateStockMovementRequest(itemId, 5);

        // 2. Настройка моков
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(stockService.receiveStock(itemId, 5)).thenReturn(15);

        // 3. Выполнение
        StockMovementResponse response = stockMovementService.registerReceipt(user, request);

        // 4. Проверки
        assertNotNull(response);
        assertEquals(itemId, response.itemId());
        assertEquals(5, response.quantity());
        assertEquals(15, response.stockAfter());

        verify(itemRepository, times(1)).findById(itemId);
        verify(stockService, times(1)).receiveStock(itemId, 5);
        verify(stockMovementRepository, times(1)).save(stockMovementCaptor.capture());

        // Проверяем, что запись движения сохранена с правильными данными
        StockMovement savedMovement = stockMovementCaptor.getValue();
        assertEquals(itemId, savedMovement.getItem().getId());
        assertEquals(user.getId(), savedMovement.getUser().getId());
        assertEquals(5, savedMovement.getQuantity());
    }

    @Test
    void registerReceiptItemNotFoundThrowsException() {
        // 1. Подготовка данных
        Long itemId = 1L;
        User user = new User();
        user.setId(1L);

        CreateStockMovementRequest request = new CreateStockMovementRequest(itemId, 5);

        // 2. Настройка моков
        when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

        // 3. Выполнение и проверка исключения
        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.registerReceipt(user, request);
        });

        // 4. Проверка деталей исключения
        assertTrue(exception.getMessage().contains("not found"));

        // Проверяем, что сервис не вызывался
        verify(stockService, times(0)).receiveStock(any(), any());
        verify(stockMovementRepository, times(0)).save(any());
    }

    @Test
    void registerReceiptInactiveItemThrowsException() {
        // 1. Подготовка данных
        Long itemId = 1L;
        User user = new User();
        user.setId(1L);

        Item inactiveItem = new Item();
        inactiveItem.setId(itemId);
        inactiveItem.setActive(false);

        CreateStockMovementRequest request = new CreateStockMovementRequest(itemId, 5);

        // 2. Настройка моков
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(inactiveItem));

        // 3. Выполнение и проверка исключения
        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.registerReceipt(user, request);
        });

        // 4. Проверка деталей исключения
        assertTrue(exception.getMessage().contains("not found"));

        // Проверяем, что сервис не вызывался
        verify(stockService, times(0)).receiveStock(any(), any());
        verify(stockMovementRepository, times(0)).save(any());
    }
}
