package com.warehouse.service.movement;

import com.warehouse.dto.request.movement.CreateStockMovementRequest;
import com.warehouse.dto.request.movement.WriteOffStockMovementRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.mapper.StockMovementMapper;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.service.stock.StockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockMovementServiceImplTest {

    @Mock
    private StockMovementMapper mapper;

    @Mock
    private StockService stockService;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @InjectMocks
    private StockMovementServiceImpl stockMovementService;

    @Captor
    private ArgumentCaptor<StockMovement> stockMovementCaptor;

    private static final Long ITEM_ID = 1L;
    private static final Long NON_EXISTENT_ITEM_ID = 999L;
    private static final int QUANTITY = 5;
    private static final int STOCK_AFTER_RECEIPT = 15;
    private static final Long USER_ID = 10L;

    @Test
    void registerReceiptSuccess() {
        // Arrange
        CreateStockMovementRequest request = new CreateStockMovementRequest(ITEM_ID, QUANTITY);
        User user = createUser(USER_ID, "admin");
        Item item = createItem(ITEM_ID, "Тестовый товар", true);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
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
        StockMovementResponse response = stockMovementService.registerReceipt(request, user);

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
        CreateStockMovementRequest request = new CreateStockMovementRequest(ITEM_ID, 0);
        User user = createUser(USER_ID, "admin");

        // Act & Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            stockMovementService.registerReceipt(request, user);
        });

        assertEquals("Quantity must be greater than 0", ex.getMessage());
    }

    @Test
    void registerReceiptWithNegativeQuantityThrowsException() {
        // Arrange
        CreateStockMovementRequest request = new CreateStockMovementRequest(ITEM_ID, -1);
        User user = createUser(USER_ID, "admin");

        // Act & Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            stockMovementService.registerReceipt(request, user);
        });

        assertEquals("Quantity must be greater than 0", ex.getMessage());
    }

    @Test
    void registerReceiptItemNotFoundThrowsException() {
        // Arrange
        CreateStockMovementRequest request = new CreateStockMovementRequest(NON_EXISTENT_ITEM_ID, QUANTITY);
        User user = createUser(USER_ID, "admin");

        when(itemRepository.findById(NON_EXISTENT_ITEM_ID)).thenReturn(Optional.empty());

        // Act & Assert
        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.registerReceipt(request, user);
        });

        assertTrue(ex.getMessage().contains("Item"));
        assertTrue(ex.getMessage().contains(String.valueOf(NON_EXISTENT_ITEM_ID)));
    }

    @Test
    void registerReceiptInactiveItemThrowsException() {
        // Arrange
        CreateStockMovementRequest request = new CreateStockMovementRequest(ITEM_ID, QUANTITY);
        User user = createUser(USER_ID, "admin");
        Item inactiveItem = createItem(ITEM_ID, "Тестовый товар", false);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(inactiveItem));

        // Act & Assert
        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.registerReceipt(request, user);
        });

        assertTrue(ex.getMessage().contains("Item"));
        assertTrue(ex.getMessage().contains(String.valueOf(ITEM_ID)));
    }

    @Test
    void registerReceiptUserNotNull() {
        // Arrange
        CreateStockMovementRequest request = new CreateStockMovementRequest(ITEM_ID, QUANTITY);
        User user = createUser(USER_ID, "admin");
        Item item = createItem(ITEM_ID, "Тестовый товар", true);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(stockService.receiveStock(ITEM_ID, QUANTITY)).thenReturn(STOCK_AFTER_RECEIPT);
        when(stockMovementRepository.save(any(StockMovement.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        stockMovementService.registerReceipt(request, user);

        // Assert
        verify(stockMovementRepository).save(stockMovementCaptor.capture());
        StockMovement savedMovement = stockMovementCaptor.getValue();
        assertNotNull(savedMovement.getUser());
        assertEquals(USER_ID, savedMovement.getUser().getId());
        assertEquals("admin", savedMovement.getUser().getUsername());
    }

    // ==========================================
//    ТЕСТЫ ДЛЯ WRITE-OFF
// ==========================================

    @Test
    void writeOffReceiptSuccess() {
        // Arrange
        WriteOffStockMovementRequest request = new WriteOffStockMovementRequest(ITEM_ID, QUANTITY);
        User user = createUser(USER_ID, "admin");
        Item item = createItem(ITEM_ID, "Тестовый товар", true);
        int stockAfterWriteOff = 5;

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
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
        StockMovementResponse response = stockMovementService.writeOffReceipt(request, user);

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
        WriteOffStockMovementRequest request = new WriteOffStockMovementRequest(NON_EXISTENT_ITEM_ID, QUANTITY);
        User user = createUser(USER_ID, "admin");

        when(itemRepository.findById(NON_EXISTENT_ITEM_ID)).thenReturn(Optional.empty());

        // Act & Assert
        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.writeOffReceipt(request, user);
        });

        assertTrue(ex.getMessage().contains("Item"));
        assertTrue(ex.getMessage().contains(String.valueOf(NON_EXISTENT_ITEM_ID)));
    }

    @Test
    void writeOffReceiptInactiveItemThrowsException() {
        // Arrange
        WriteOffStockMovementRequest request = new WriteOffStockMovementRequest(ITEM_ID, QUANTITY);
        User user = createUser(USER_ID, "admin");
        Item inactiveItem = createItem(ITEM_ID, "Тестовый товар", false);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(inactiveItem));

        // Act & Assert
        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () -> {
            stockMovementService.writeOffReceipt(request, user);
        });

        assertTrue(ex.getMessage().contains("Item"));
        assertTrue(ex.getMessage().contains(String.valueOf(ITEM_ID)));
    }

    @Test
    void writeOffReceiptInsufficientStockThrowsException() {
        // Arrange
        WriteOffStockMovementRequest request = new WriteOffStockMovementRequest(ITEM_ID, 20);
        User user = createUser(USER_ID, "admin");
        Item item = createItem(ITEM_ID, "Тестовый товар", true);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(stockService.writeOffStock(ITEM_ID, 20))
                .thenThrow(new InsufficientStockException("Insufficient stock"));

        // Act & Assert
        InsufficientStockException ex = assertThrows(InsufficientStockException.class, () -> {
            stockMovementService.writeOffReceipt(request, user);
        });

        assertEquals("Insufficient stock", ex.getMessage());
    }

    @Test
    void writeOffReceiptUserNotNull() {
        // Arrange
        WriteOffStockMovementRequest request = new WriteOffStockMovementRequest(ITEM_ID, QUANTITY);
        User user = createUser(USER_ID, "admin");
        Item item = createItem(ITEM_ID, "Тестовый товар", true);
        int stockAfterWriteOff = 5;

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(stockService.writeOffStock(ITEM_ID, QUANTITY)).thenReturn(stockAfterWriteOff);
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        stockMovementService.writeOffReceipt(request, user);

        // Assert
        verify(stockMovementRepository).save(stockMovementCaptor.capture());
        StockMovement savedMovement = stockMovementCaptor.getValue();
        assertNotNull(savedMovement.getUser());
        assertEquals(USER_ID, savedMovement.getUser().getId());
        assertEquals("admin", savedMovement.getUser().getUsername());
    }

    // ==========================================
    //    ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ==========================================

    private User createUser(Long userId, String username) {
        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword("password");
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
