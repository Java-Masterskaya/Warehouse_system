package com.warehouse.service;

import com.warehouse.dto.request.CreateStockMovementRequest;
import com.warehouse.dto.response.StockMovementResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.StockMovementMapper;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
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

/**
 * Unit-тесты для StockMovementServiceImpl.
 * Проверяют логику пополнения остатков товара с использованием моков.
 */
@ExtendWith(MockitoExtension.class)
class StockMovementServiceImplTest {

    @Mock
    private StockRepository stockRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    private StockMovementService stockMovementService;

    @BeforeEach
    void setUp() {
        StockMovementMapper mapper = Mappers.getMapper(StockMovementMapper.class);
        stockMovementService = new StockMovementServiceImpl(
            mapper,
            stockRepository,
            stockMovementRepository
        );
    }

    /**
     * Тест: Успешное пополнение остатков товара.
     */
    @Test
    void receiveStockSuccess() {
        Long itemId = 1L;
        int quantity = 10;
        User user = User.builder()
            .id(1L)
            .username("admin")
            .role(Role.ROLE_ADMIN)
            .build();

        CreateStockMovementRequest request = new CreateStockMovementRequest(itemId, quantity);

        Item item = Item.builder()
            .id(itemId)
            .name("Тестовый товар")
            .category("Категория")
            .active(true)
            .build();

        Stock stock = Stock.builder()
            .item(item)
            .quantity(50)
            .build();

        when(stockRepository.findWithItemById(itemId)).thenReturn(Optional.of(stock));
        when(stockMovementRepository.save(any(StockMovement.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockMovementResponse result = stockMovementService.receiveStock(user, request);

        assertNotNull(result);
        assertEquals(itemId, result.itemId());
        assertEquals(quantity, result.quantity());
        assertEquals(MovementType.RECEIVE, result.type());
        assertEquals(60, result.stockAfter());

        verify(stockRepository, times(1)).findWithItemById(itemId);
        verify(stockMovementRepository, times(1)).save(any(StockMovement.class));
        verify(stockRepository, times(1)).save(any(Stock.class));
    }

    /**
     * Тест: Исключение при отсутствии остатка на складе.
     */
    @Test
    void receiveStockStockNotFoundThrowsException() {
        Long itemId = 1L;
        User user = User.builder()
            .id(1L)
            .build();

        CreateStockMovementRequest request = new CreateStockMovementRequest(itemId, 10);

        when(stockRepository.findWithItemById(itemId)).thenReturn(Optional.empty());

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.receiveStock(user, request);
        });

        assertTrue(exception.getMessage().contains("Stock"));
    }

    /**
     * Тест: Исключение при попытке пополнить неактивный товар.
     */
    @Test
    void receiveStockItemInactiveThrowsException() {
        Long itemId = 1L;
        User user = User.builder()
            .id(1L)
            .build();

        CreateStockMovementRequest request = new CreateStockMovementRequest(itemId, 10);

        Item inactiveItem = Item.builder()
            .id(itemId)
            .active(false)
            .build();

        Stock stock = Stock.builder()
            .item(inactiveItem)
            .quantity(50)
            .build();

        when(stockRepository.findWithItemById(itemId)).thenReturn(Optional.of(stock));

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.receiveStock(user, request);
        });

        assertTrue(exception.getMessage().contains("Item"));
    }
}