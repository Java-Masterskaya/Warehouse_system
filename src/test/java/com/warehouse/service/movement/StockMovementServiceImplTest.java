package com.warehouse.service.movement;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.movement.StockMovementHistoryResponse;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.mapper.StockMovementMapper;
import com.warehouse.metric.MetricService;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.stock.StockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class StockMovementServiceImplTest {

    private static final Long ITEM_ID = 1L;
    private static final Long NON_EXISTENT_ITEM_ID = 999L;
    private static final int QUANTITY = 5;
    private static final int STOCK_AFTER_RECEIPT = 15;
    private static final Long USER_ID = 10L;
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "password";
    @Mock
    private StockMovementMapper mapper;
    @Mock
    private StockService stockService;
    @Mock
    private ItemRepository itemRepository;
    @Mock
    private StockMovementRepository stockMovementRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MetricService metricService;
    @InjectMocks
    private StockMovementServiceImpl stockMovementService;
    @Captor
    private ArgumentCaptor<StockMovement> stockMovementCaptor;

    /**
     * Успешная регистрация прихода товара.
     */
    @Test
    void registerReceiptSuccess() {
        // Arrange
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(ITEM_ID, QUANTITY);
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Тестовый товар", true);
        User userRef = createUserReference(USER_ID, USERNAME);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(stockService.receiveStock(ITEM_ID, QUANTITY)).thenReturn(STOCK_AFTER_RECEIPT);
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(mapper.toResponse(any(StockMovement.class), eq(STOCK_AFTER_RECEIPT)))
                .thenAnswer(invocation -> {
                    StockMovement movement = invocation.getArgument(0);
                    int stockAfter = invocation.getArgument(1);
                    return new StockMovementResponse(
                            movement.getItem().getId(),
                            movement.getId(),
                            movement.getType(),
                            movement.getQuantity(),
                            stockAfter,
                            movement.getCreatedAt()
                    );
                });

        // Act
        StockMovementResponse response = stockMovementService.registerReceipt(request, userContext);

        // Assert
        assertNotNull(response);
        assertEquals(ITEM_ID, response.itemId());
        assertEquals(QUANTITY, response.quantity());
        assertEquals(STOCK_AFTER_RECEIPT, response.stockAfter());
        assertEquals(MovementType.RECEIVE, response.type());

        verify(stockMovementRepository).save(stockMovementCaptor.capture());
        StockMovement savedMovement = stockMovementCaptor.getValue();
        assertEquals(ITEM_ID, savedMovement.getItem().getId());
        assertEquals(USER_ID, savedMovement.getUser().getId());
        assertEquals(MovementType.RECEIVE, savedMovement.getType());
        assertEquals(QUANTITY, savedMovement.getQuantity());
    }

    @Test
    void registerReceiptWithZeroQuantityThrowsException() {
        // Arrange
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(ITEM_ID, 0);
        UserContext userContext = new UserContext(USER_ID, USERNAME);

        // Act & Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            stockMovementService.registerReceipt(request, userContext);
        });

        assertEquals("Quantity must be greater than 0", ex.getMessage());
    }

    @Test
    void registerReceiptWithNegativeQuantityThrowsException() {
        // Arrange
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(ITEM_ID, -1);
        UserContext userContext = new UserContext(USER_ID, USERNAME);

        // Act & Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            stockMovementService.registerReceipt(request, userContext);
        });

        assertEquals("Quantity must be greater than 0", ex.getMessage());
    }

    @Test
    void registerReceiptItemNotFoundThrowsException() {
        // Arrange
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(NON_EXISTENT_ITEM_ID, QUANTITY);
        UserContext userContext = new UserContext(USER_ID, USERNAME);

        when(itemRepository.findById(NON_EXISTENT_ITEM_ID)).thenReturn(Optional.empty());

        // Act & Assert
        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.registerReceipt(request, userContext);
        });

        assertTrue(ex.getMessage().contains("Item"));
        assertTrue(ex.getMessage().contains(String.valueOf(NON_EXISTENT_ITEM_ID)));
    }

    @Test
    void registerReceiptInactiveItemThrowsException() {
        // Arrange
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(ITEM_ID, QUANTITY);
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item inactiveItem = createItem(ITEM_ID, "Тестовый товар", false);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(inactiveItem));

        // Act & Assert
        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.registerReceipt(request, userContext);
        });

        assertTrue(ex.getMessage().contains("Item"));
        assertTrue(ex.getMessage().contains(String.valueOf(ITEM_ID)));
    }

    @Test
    void registerReceiptUserNotNull() {
        // Arrange
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(ITEM_ID, QUANTITY);
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Тестовый товар", true);
        User userRef = createUserReference(USER_ID, USERNAME);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(stockService.receiveStock(ITEM_ID, QUANTITY)).thenReturn(STOCK_AFTER_RECEIPT);
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        stockMovementService.registerReceipt(request, userContext);

        // Assert
        verify(stockMovementRepository).save(stockMovementCaptor.capture());
        StockMovement savedMovement = stockMovementCaptor.getValue();
        assertNotNull(savedMovement.getUser());
        assertEquals(USER_ID, savedMovement.getUser().getId());
        assertEquals(USERNAME, savedMovement.getUser().getUsername());
    }

    // ==========================================
//    ТЕСТЫ ДЛЯ WRITE-OFF
// ==========================================

    @Test
    void writeOffReceiptSuccess() {
        // Arrange
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(ITEM_ID, QUANTITY);
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Тестовый товар", true);
        User userRef = createUserReference(USER_ID, USERNAME);
        int stockAfterWriteOff = 5;

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(stockService.writeOffStock(ITEM_ID, QUANTITY)).thenReturn(stockAfterWriteOff);
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(mapper.toResponse(any(StockMovement.class), eq(stockAfterWriteOff)))
                .thenAnswer(invocation -> {
                    StockMovement movement = invocation.getArgument(0);
                    int stockAfter = invocation.getArgument(1);
                    return new StockMovementResponse(
                            movement.getItem().getId(),
                            movement.getId(),
                            movement.getType(),
                            movement.getQuantity(),
                            stockAfter,
                            movement.getCreatedAt()
                    );
                });

        // Act
        StockMovementResponse response = stockMovementService.writeOffReceipt(request, userContext);

        // Assert
        assertNotNull(response);
        assertEquals(ITEM_ID, response.itemId());
        assertEquals(QUANTITY, response.quantity());
        assertEquals(stockAfterWriteOff, response.stockAfter());
        assertEquals(MovementType.WRITE_OFF, response.type());

        verify(stockMovementRepository).save(stockMovementCaptor.capture());
        StockMovement savedMovement = stockMovementCaptor.getValue();
        assertEquals(ITEM_ID, savedMovement.getItem().getId());
        assertEquals(USER_ID, savedMovement.getUser().getId());
        assertEquals(MovementType.WRITE_OFF, savedMovement.getType());
        assertEquals(QUANTITY, savedMovement.getQuantity());
    }

    @Test
    void writeOffReceiptItemNotFoundThrowsException() {
        // Arrange
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(NON_EXISTENT_ITEM_ID, QUANTITY);
        UserContext userContext = new UserContext(USER_ID, USERNAME);

        when(itemRepository.findById(NON_EXISTENT_ITEM_ID)).thenReturn(Optional.empty());

        // Act & Assert
        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.writeOffReceipt(request, userContext);
        });

        assertTrue(ex.getMessage().contains("Item"));
        assertTrue(ex.getMessage().contains(String.valueOf(NON_EXISTENT_ITEM_ID)));
    }

    @Test
    void writeOffReceiptInactiveItemThrowsException() {
        // Arrange
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(ITEM_ID, QUANTITY);
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item inactiveItem = createItem(ITEM_ID, "Тестовый товар", false);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(inactiveItem));

        // Act & Assert
        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.writeOffReceipt(request, userContext);
        });

        assertTrue(ex.getMessage().contains("Item"));
        assertTrue(ex.getMessage().contains(String.valueOf(ITEM_ID)));
    }

    @Test
    void writeOffReceiptInsufficientStockThrowsException() {
        // Arrange
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(ITEM_ID, 20);
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Тестовый товар", true);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(stockService.writeOffStock(ITEM_ID, 20))
                .thenThrow(new InsufficientStockException("Insufficient stock"));

        // Act & Assert
        InsufficientStockException ex = assertThrows(InsufficientStockException.class, () -> {
            stockMovementService.writeOffReceipt(request, userContext);
        });

        assertEquals("Insufficient stock", ex.getMessage());
    }

    @Test
    void writeOffReceiptUserNotNull() {
        // Arrange
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(ITEM_ID, QUANTITY);
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Тестовый товар", true);
        User userRef = createUserReference(USER_ID, USERNAME);
        int stockAfterWriteOff = 5;

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(stockService.writeOffStock(ITEM_ID, QUANTITY)).thenReturn(stockAfterWriteOff);
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        stockMovementService.writeOffReceipt(request, userContext);

        // Assert
        verify(stockMovementRepository).save(stockMovementCaptor.capture());
        StockMovement savedMovement = stockMovementCaptor.getValue();
        assertNotNull(savedMovement.getUser());
        assertEquals(USER_ID, savedMovement.getUser().getId());
        assertEquals(USERNAME, savedMovement.getUser().getUsername());
    }

    @Test
    void getItemMovementHistorySuccess() {
        Long itemId = 1L;
        MovementType type = MovementType.WRITE_OFF;
        int page = 0;
        int size = 20;

        StockMovementHistoryResponse movement =
                new StockMovementHistoryResponse(
                        102L,
                        MovementType.WRITE_OFF,
                        10,
                        "admin",
                        LocalDateTime.of(2026, 5, 28, 11, 30)
                );

        Page<StockMovementHistoryResponse> historyPage =
                new PageImpl<>(
                        List.of(movement),
                        PageRequest.of(page, size),
                        1
                );

        when(itemRepository.existsById(itemId))
                .thenReturn(true);

        when(stockMovementRepository.findHistoryByItemId(
                eq(itemId),
                eq(type),
                any(Pageable.class)
        )).thenReturn(historyPage);

        PageResponse<StockMovementHistoryResponse> result =
                stockMovementService.getItemMovementHistory(
                        itemId,
                        type,
                        page,
                        size
                );

        assertEquals(1, result.content().size());
        assertEquals(1, result.totalElements());
        assertEquals(1, result.totalPages());
        assertEquals(0, result.page());
        assertEquals(20, result.size());

        StockMovementHistoryResponse response = result.content().get(0);

        assertEquals(102L, response.id());
        assertEquals(MovementType.WRITE_OFF, response.type());
        assertEquals(10, response.quantity());
        assertEquals("admin", response.performedBy());

        verify(itemRepository).existsById(itemId);
        verify(stockMovementRepository).findHistoryByItemId(
                eq(itemId),
                eq(type),
                any(Pageable.class)
        );
    }

    @Test
    void getItemMovementHistoryItemNotFound() {
        Long itemId = 999L;
        MovementType type = MovementType.RECEIVE;
        int page = 0;
        int size = 20;

        when(itemRepository.existsById(itemId))
                .thenReturn(false);

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> stockMovementService.getItemMovementHistory(
                                itemId,
                                type,
                                page,
                                size
                        )
                );

        assertEquals(
                "Item with id 999 not found",
                exception.getMessage()
        );

        verify(itemRepository).existsById(itemId);

        verify(stockMovementRepository, never()).findHistoryByItemId(
                anyLong(),
                any(),
                any(Pageable.class)
        );
    }

    // ==========================================
    //    ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ==========================================

    private User createUserReference(Long userId, String username) {
        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword(PASSWORD);
        user.setRole(com.warehouse.entity.Role.ROLE_ADMIN);
        user.setActive(true);
        return user;
    }

    private Item createItem(Long itemId, String name, boolean active) {
        Item item = new Item();
        item.setId(itemId);
        item.setName(name);
        item.setActive(active);
        return item;
    }

}
