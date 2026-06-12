package com.warehouse.service.movement;

import com.warehouse.dto.request.movement.CreateStockMovementRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.StockMovementMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.service.stock.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тест для проверки бизнес-логики сервиса движения товаров.
 * Тестирует метод регистрации прихода товара без HTTP-слоя и реальной БД.
 */
@ExtendWith(MockitoExtension.class)
class StockMovementServiceImplTest {

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private StockService stockService;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @Mock
    private StockMovementMapper stockMovementMapper;

    private StockMovementService stockMovementService;

    @Captor
    private ArgumentCaptor<StockMovement> stockMovementCaptor;

    @BeforeEach
    void setUp() {
        StockMovementMapper mapper = Mappers.getMapper(StockMovementMapper.class);
        stockMovementService = new StockMovementServiceImpl(mapper, stockService, itemRepository, stockMovementRepository);
        when(stockMovementRepository.save(any(StockMovement.class)))
            .thenAnswer(invocation -> {
                StockMovement movement = invocation.getArgument(0);
                movement.setId(1L);
                return movement;
            });
    }

    /**
     * Успешная регистрация прихода товара:
     * - Товар найден и активен
     * - Остаток обновляется
     * - Запись движения сохраняется с правильными данными
     */
    @Test
    void registerReceipt_success() {
        Long itemId = 1L;
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");

        Item item = new Item();
        item.setId(itemId);
        item.setActive(true);

        CreateStockMovementRequest request = new CreateStockMovementRequest(itemId, 5);

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(stockService.receiveStock(itemId, 5)).thenReturn(15);
        when(stockMovementMapper.toResponse(any(StockMovement.class), eq(15)))
            .thenReturn(new StockMovementResponse(
                itemId,
                1L,
                MovementType.RECEIVE,
                5,
                15,
                null
            ));

        StockMovementResponse response = stockMovementService.registerReceipt(user, request);

        assertNotNull(response);
        assertEquals(itemId, response.itemId());
        assertEquals(5, response.quantity());
        assertEquals(15, response.stockAfter());

        verify(itemRepository, times(1)).findById(itemId);
        verify(stockService, times(1)).receiveStock(itemId, 5);
        verify(stockMovementRepository, times(1)).save(stockMovementCaptor.capture());
        verify(stockMovementMapper, times(1)).toResponse(stockMovementCaptor.capture(), any());

        StockMovement savedMovement = stockMovementCaptor.getValue();
        assertNotNull(savedMovement.getId());
        assertEquals(itemId, savedMovement.getItem().getId());
        assertEquals(user.getId(), savedMovement.getUser().getId());
        assertEquals(5, savedMovement.getQuantity());
        assertEquals(MovementType.RECEIVE, savedMovement.getType());
    }

    /**
     * При попытке зарегистрировать приход для несуществующего товара
     * выбрасывается EntityNotFoundException.
     */
    @Test
    void registerReceipt_itemNotFound_throwsException() {
        Long itemId = 1L;
        User user = new User();
        user.setId(1L);

        CreateStockMovementRequest request = new CreateStockMovementRequest(itemId, 5);

        when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.registerReceipt(user, request);
        });

        assertTrue(exception.getMessage().contains("not found"));

        verify(stockService, times(0)).receiveStock(any(), any());
        verify(stockMovementRepository, times(0)).save(any());
    }

    /**
     * При попытке зарегистрировать приход для неактивного товара
     * выбрасывается EntityNotFoundException.
     */
    @Test
    void registerReceipt_inactiveItem_throwsException() {
        Long itemId = 1L;
        User user = new User();
        user.setId(1L);

        Item inactiveItem = new Item();
        inactiveItem.setId(itemId);
        inactiveItem.setActive(false);

        CreateStockMovementRequest request = new CreateStockMovementRequest(itemId, 5);

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(inactiveItem));

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.registerReceipt(user, request);
        });

        assertTrue(exception.getMessage().contains("not found"));

        verify(stockService, times(0)).receiveStock(any(), any());
        verify(stockMovementRepository, times(0)).save(any());
    }
}
