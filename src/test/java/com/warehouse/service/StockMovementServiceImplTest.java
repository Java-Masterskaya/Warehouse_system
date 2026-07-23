package com.warehouse.service;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.request.movement.StocktakeRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.movement.StockMovementHistoryResponse;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.exception.InvalidMovementRequestException;
import com.warehouse.exception.StocktakeConflictException;
import com.warehouse.mapper.StockMovementMapper;
import com.warehouse.metric.MetricService;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.batch.BatchService;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.service.movement.StockMovementServiceImpl;
import com.warehouse.kafka.outbox.OutboxService;
import com.warehouse.service.reservation.StockAvailabilityService;
import com.warehouse.service.stock.StockService;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тест для StockMovementServiceImpl.
 * Тестирует операции регистрации прихода и списания товаров, а также outbox low stock alert.
 */
@ExtendWith(MockitoExtension.class)
class StockMovementServiceImplTest {

    private static final Long ITEM_ID = 1L;
    private static final Long NON_EXISTENT_ITEM_ID = 999L;
    private static final int QUANTITY = 5;
    private static final int STOCK_AFTER_RECEIPT = 15;
    private static final Long USER_ID = 10L;
    private static final Long DEFAULT_WAREHOUSE_ID = 1L;
    private static final Long SECONDARY_WAREHOUSE_ID = 2L;
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "password";

    @Mock
    private StockMovementMapper mapper;
    @Mock
    private StockService stockService;
    @Mock
    private StockAvailabilityService availabilityService;
    @Mock
    private BatchService batchService;
    @Mock
    private com.warehouse.repository.BatchRepository batchRepository;
    @Mock
    private ItemRepository itemRepository;
    @Mock
    private StockRepository stockRepository;
    @Mock
    private StockMovementRepository stockMovementRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private WarehouseRepository warehouseRepository;
    @Mock
    private OutboxService outboxService;
    @Mock
    private MetricService metricService;
    @InjectMocks
    private StockMovementServiceImpl stockMovementService;
    @Captor
    private ArgumentCaptor<StockMovement> stockMovementCaptor;
    @Captor
    private ArgumentCaptor<LowStockAlertEvent> eventCaptor;
    @Captor
    private ArgumentCaptor<List<StockMovement>> stockMovementsCaptor;

    private Warehouse defaultWarehouse;

    @BeforeEach
    void setUp() {
        defaultWarehouse = Warehouse.builder()
                .id(DEFAULT_WAREHOUSE_ID)
                .name("Default Warehouse")
                .defaultWarehouse(true)
                .build();
        lenient().when(warehouseRepository.findByDefaultWarehouseTrue())
                .thenReturn(Optional.of(defaultWarehouse));
    }

    /**
     * Регистрация прихода товара.
     */
    @Test
    void registerReceiptSuccess() {
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(ITEM_ID, QUANTITY, LocalDateTime.now()
                .plusDays(1));
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Тестовый товар", true, 0);
        User userRef = createUserReference(USER_ID, USERNAME);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(batchService.createBatchAndIncreaseStock(any(Item.class), eq(QUANTITY), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    Item i = invocation.getArgument(0);
                    int q = invocation.getArgument(1);
                    return Batch.builder().id(1L).item(i).quantity(q).expiryDate(invocation.getArgument(2)).build();
                });
        when(stockRepository.findQuantityByItemId(ITEM_ID)).thenReturn(Optional.of(STOCK_AFTER_RECEIPT));
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.toResponse(any(StockMovement.class), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> {
                    StockMovement movement = invocation.getArgument(0);
                    int stockAfter = invocation.getArgument(1);
                    boolean lowStockAlert = invocation.getArgument(2);
                    return new StockMovementResponse(
                            movement.getItem().getId(), movement.getId(),
                            movement.getType(), movement.getQuantity(),
                            stockAfter, null, null, movement.getCreatedAt(), lowStockAlert,
                            null, null, null);
                });

        StockMovementResponse response = stockMovementService.registerReceipt(request, userContext);

        assertNotNull(response);
        assertEquals(ITEM_ID, response.itemId());
        assertEquals(QUANTITY, response.quantity());
        assertEquals(STOCK_AFTER_RECEIPT, response.stockAfter());
        assertEquals(MovementType.RECEIVE, response.type());
        assertFalse(response.lowStockAlert());

        verify(stockMovementRepository).save(stockMovementCaptor.capture());
        StockMovement savedMovement = stockMovementCaptor.getValue();
        assertEquals(ITEM_ID, savedMovement.getItem().getId());
        assertEquals(USER_ID, savedMovement.getUser().getId());
        assertEquals(DEFAULT_WAREHOUSE_ID, savedMovement.getWarehouse().getId());
        assertEquals(MovementType.RECEIVE, savedMovement.getType());
        assertEquals(QUANTITY, savedMovement.getQuantity());
    }

    /**
     * Попытка зарегистрировать приход с количеством = 0 выбрасывает InvalidMovementRequestException.
     */
    @Test
    void registerReceiptWithZeroQuantityThrowsException() {
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(ITEM_ID,
                0, LocalDateTime.now());
        UserContext userContext = new UserContext(USER_ID, USERNAME);

        InvalidMovementRequestException ex = assertThrows(InvalidMovementRequestException.class,
                () -> stockMovementService.registerReceipt(request, userContext));

        assertEquals("Quantity must be greater than 0", ex.getMessage());
    }

    /**
     * Количество < 0 выбрасывает InvalidMovementRequestException.
     */
    @Test
    void registerReceiptWithNegativeQuantityThrowsException() {
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(ITEM_ID, -1, LocalDateTime.now());
        UserContext userContext = new UserContext(USER_ID, USERNAME);

        InvalidMovementRequestException ex = assertThrows(InvalidMovementRequestException.class,
                () -> stockMovementService.registerReceipt(request, userContext));

        assertEquals("Quantity must be greater than 0", ex.getMessage());
    }

    /**
     * Товар не найден выбрасывает EntityNotFoundException.
     */
    @Test
    void registerReceiptItemNotFoundThrowsException() {
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(
                NON_EXISTENT_ITEM_ID, QUANTITY, LocalDateTime.now());
        UserContext userContext = new UserContext(USER_ID, USERNAME);

        when(itemRepository.findById(NON_EXISTENT_ITEM_ID)).thenReturn(Optional.empty());

        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class,
                () -> stockMovementService.registerReceipt(request, userContext));

        assertTrue(ex.getMessage().contains("Item"));
        assertTrue(ex.getMessage().contains(String.valueOf(NON_EXISTENT_ITEM_ID)));
    }

    /**
     * Неактивный товар выбрасывает EntityNotFoundException.
     */
    @Test
    void registerReceiptInactiveItemThrowsException() {
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(
                ITEM_ID, QUANTITY, LocalDateTime.now());
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item inactiveItem = createItem(ITEM_ID, "Тестовый товар", false, 0);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(inactiveItem));

        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class,
                () -> stockMovementService.registerReceipt(request, userContext));

        assertTrue(ex.getMessage().contains("Item"));
        assertTrue(ex.getMessage().contains(String.valueOf(ITEM_ID)));
    }

    /**
     * Пользователь в request корректно устанавливается в сущность движения.
     */
    @Test
    void registerReceiptUserNotNull() {
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(
                ITEM_ID, QUANTITY, LocalDateTime.now()
                .plusDays(1));
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Тестовый товар", true, 0);
        User userRef = createUserReference(USER_ID, USERNAME);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(batchService.createBatchAndIncreaseStock(any(Item.class), eq(QUANTITY), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    Item i = invocation.getArgument(0);
                    int q = invocation.getArgument(1);
                    return Batch.builder().id(1L).item(i).quantity(q).expiryDate(
                            invocation.getArgument(2)).build();
                });
        when(stockRepository.findQuantityByItemId(ITEM_ID)).thenReturn(Optional.of(STOCK_AFTER_RECEIPT));
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        stockMovementService.registerReceipt(request, userContext);

        verify(stockMovementRepository).save(stockMovementCaptor.capture());
        StockMovement savedMovement = stockMovementCaptor.getValue();
        assertNotNull(savedMovement.getUser());
        assertEquals(USER_ID, savedMovement.getUser().getId());
        assertEquals(USERNAME, savedMovement.getUser().getUsername());
    }

    /**
     * Регистрация списания товара.
     */
    @Test
    void writeOffReceiptSuccess() {
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(
                ITEM_ID, QUANTITY, LocalDateTime.now());
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Тестовый товар", true, 0);
        User userRef = createUserReference(USER_ID, USERNAME);
        int stockAfterWriteOff = 5;

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(batchService.writeOffByFEFO(eq(ITEM_ID), eq(QUANTITY),
                any(LocalDateTime.class))).thenReturn(stockAfterWriteOff);
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.toResponse(any(StockMovement.class), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> {
                    StockMovement movement = invocation.getArgument(0);
                    int stockAfter = invocation.getArgument(1);
                    boolean lowStockAlert = invocation.getArgument(2);
                    return new StockMovementResponse(
                            movement.getItem().getId(), movement.getId(),
                            movement.getType(), movement.getQuantity(),
                            stockAfter, null, null, movement.getCreatedAt(), lowStockAlert,
                            null, null, null);
                });

        StockMovementResponse response = stockMovementService.writeOffReceipt(request, userContext);

        assertNotNull(response);
        assertEquals(ITEM_ID, response.itemId());
        assertEquals(QUANTITY, response.quantity());
        assertEquals(stockAfterWriteOff, response.stockAfter());
        assertEquals(MovementType.WRITE_OFF, response.type());
        assertFalse(response.lowStockAlert());
    }

    /**
     * Товар не найден при списании выбрасывает EntityNotFoundException.
     */
    @Test
    void writeOffReceiptItemNotFoundThrowsException() {
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(
                NON_EXISTENT_ITEM_ID, QUANTITY, LocalDateTime.now());
        UserContext userContext = new UserContext(USER_ID, USERNAME);

        when(itemRepository.findById(NON_EXISTENT_ITEM_ID)).thenReturn(Optional.empty());

        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class,
                () -> stockMovementService.writeOffReceipt(request, userContext));

        assertTrue(ex.getMessage().contains("Item"));
        assertTrue(ex.getMessage().contains(String.valueOf(NON_EXISTENT_ITEM_ID)));
    }

    /**
     * Неактивный товар при списании выбрасывает EntityNotFoundException.
     */
    @Test
    void writeOffReceiptInactiveItemThrowsException() {
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(
                ITEM_ID, QUANTITY, LocalDateTime.now());
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item inactiveItem = createItem(ITEM_ID, "Тестовый товар", false, 0);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(inactiveItem));

        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class,
                () -> stockMovementService.writeOffReceipt(request, userContext));

        assertTrue(ex.getMessage().contains("Item"));
        assertTrue(ex.getMessage().contains(String.valueOf(ITEM_ID)));
    }

    /**
     * Недостаточный остаток выбрасывает InsufficientStockException.
     */
    @Test
    void writeOffReceiptInsufficientStockThrowsException() {
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(
                ITEM_ID, 20, LocalDateTime.now());
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Тестовый товар", true, 0);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(batchService.writeOffByFEFO(eq(ITEM_ID), eq(20), any(LocalDateTime.class)))
                .thenThrow(new InsufficientStockException("Insufficient stock"));

        InsufficientStockException ex = assertThrows(InsufficientStockException.class,
                () -> stockMovementService.writeOffReceipt(request, userContext));

        assertEquals("Insufficient stock", ex.getMessage());
    }

    /**
     * Пользователь в request корректно устанавливается в сущность движения при списании.
     */
    @Test
    void writeOffReceiptUserNotNull() {
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(
                ITEM_ID, QUANTITY, LocalDateTime.now());
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Тестовый товар", true, 0);
        User userRef = createUserReference(USER_ID, USERNAME);
        int stockAfterWriteOff = 5;

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(batchService.writeOffByFEFO(eq(ITEM_ID), eq(QUANTITY),
                any(LocalDateTime.class))).thenReturn(stockAfterWriteOff);
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        stockMovementService.writeOffReceipt(request, userContext);

        verify(stockMovementRepository).save(stockMovementCaptor.capture());
        StockMovement savedMovement = stockMovementCaptor.getValue();
        assertNotNull(savedMovement.getUser());
        assertEquals(USER_ID, savedMovement.getUser().getId());
        assertEquals(USERNAME, savedMovement.getUser().getUsername());
    }

    /**
     * История движения товара возвращается корректно.
     */
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

    /**
     * История для несуществующего товара выбрасывает EntityNotFoundException.
     */
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

    /**
     * При списании ниже minStock событие сохраняется в outbox.
     */
    @Test
    void writeOffReceiptBelowMinStockSavesToOutbox() {
        int minStock = 10;
        int stockAfterWriteOff = 3;
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(
                ITEM_ID, QUANTITY, LocalDateTime.now());
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Ноутбук", true, minStock);
        User userRef = createUserReference(USER_ID, USERNAME);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(batchService.writeOffByFEFO(eq(ITEM_ID), eq(QUANTITY),
                any(LocalDateTime.class))).thenReturn(stockAfterWriteOff);
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.toResponse(any(StockMovement.class), anyInt(), anyBoolean()))
                .thenReturn(new StockMovementResponse(
                        ITEM_ID, null, MovementType.WRITE_OFF, QUANTITY,
                        stockAfterWriteOff, null, null, null, true,
                        null, null, null));

        StockMovementResponse response = stockMovementService.writeOffReceipt(request, userContext);

        assertTrue(response.lowStockAlert());
        verify(outboxService).saveLowStockAlertEvent(eventCaptor.capture());
        LowStockAlertEvent savedEvent = eventCaptor.getValue();
        assertEquals(ITEM_ID, savedEvent.itemId());
        assertEquals(stockAfterWriteOff, savedEvent.currentStock());
        assertEquals(minStock, savedEvent.minStock());
    }

    /**
     * При списании выше minStock событие не сохраняется в outbox.
     */
    @Test
    void writeOffReceiptAboveMinStockDoesNotSaveToOutbox() {
        int minStock = 5;
        int stockAfterWriteOff = 10;
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(
                ITEM_ID, QUANTITY, LocalDateTime.now());
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Ноутбук", true, minStock);
        User userRef = createUserReference(USER_ID, USERNAME);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(batchService.writeOffByFEFO(eq(ITEM_ID), eq(QUANTITY),
                any(LocalDateTime.class))).thenReturn(stockAfterWriteOff);
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.toResponse(any(StockMovement.class), anyInt(), anyBoolean()))
                .thenReturn(new StockMovementResponse(
                        ITEM_ID, null, MovementType.WRITE_OFF, QUANTITY,
                        stockAfterWriteOff, null, null, null, false,
                        null, null, null));

        StockMovementResponse response = stockMovementService.writeOffReceipt(request, userContext);

        assertFalse(response.lowStockAlert());
        verify(outboxService, never()).saveLowStockAlertEvent(any());
    }

    /**
     * При списании равном minStock alert не отправляется (граничный случай).
     */
    @Test
    void writeOffReceiptEqualToMinStockDoesNotSendAlert() {
        int minStock = 5;
        int stockAfterWriteOff = 5;
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(
                ITEM_ID, QUANTITY, LocalDateTime.now());
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Ноутбук", true, minStock);
        User userRef = createUserReference(USER_ID, USERNAME);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(batchService.writeOffByFEFO(eq(ITEM_ID), eq(QUANTITY),
                any(LocalDateTime.class))).thenReturn(stockAfterWriteOff);
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.toResponse(any(StockMovement.class), anyInt(), anyBoolean()))
                .thenReturn(new StockMovementResponse(
                        ITEM_ID, null, MovementType.WRITE_OFF, QUANTITY,
                        stockAfterWriteOff, null, null, null, false,
                         null, null, null));

        StockMovementResponse response = stockMovementService.writeOffReceipt(request, userContext);

        assertFalse(response.lowStockAlert());
        verify(outboxService, never()).saveLowStockAlertEvent(any());
    }

    /**
     * При списании равном minStock событие не сохраняется в outbox (граничный случай).
     */
    @Test
    void writeOffReceiptEqualToMinStockDoesNotSaveToOutbox() {
        int minStock = 5;
        int stockAfterWriteOff = 5;
        ChangeQuantityMovementRequest request = new ChangeQuantityMovementRequest(
                ITEM_ID, QUANTITY, LocalDateTime.now());
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Ноутбук", true, minStock);
        User userRef = createUserReference(USER_ID, USERNAME);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(batchService.writeOffByFEFO(eq(ITEM_ID), eq(QUANTITY),
                any(LocalDateTime.class))).thenReturn(stockAfterWriteOff);
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.toResponse(any(StockMovement.class), anyInt(), anyBoolean()))
                .thenReturn(new StockMovementResponse(
                        ITEM_ID, null, MovementType.WRITE_OFF, QUANTITY,
                        stockAfterWriteOff, null, null, null,
                        false, null, null, null));

        StockMovementResponse response = stockMovementService.writeOffReceipt(request, userContext);

        assertFalse(response.lowStockAlert());
        verify(outboxService, never()).saveLowStockAlertEvent(any());
    }

    /**
     * Инвентаризация: фактический остаток МЕНЬШЕ учётного.
     * Создаётся отрицательное движение ADJUSTMENT, остаток уменьшается.
     */
    @Test
    void stocktakeShouldDecreaseStockWhenCountedLess() {
        StocktakeRequest request = new StocktakeRequest(ITEM_ID, 7);
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Test", true, 5);
        Stock stock = new Stock();
        stock.setItem(item);
        stock.setQuantity(10);
        User userRef = createUserReference(USER_ID, USERNAME);

        // Создаем партию для инвентаризации
        Batch batch = createBatch(ITEM_ID, 1L, 10, LocalDateTime.now().plusDays(30));

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(stockRepository.findByItemIdForUpdate(ITEM_ID)).thenReturn(Optional.of(stock));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(batchService.findByItemIdOrderByExpiryDate(ITEM_ID)).thenReturn(List.of(batch));
        when(batchRepository.save(any(Batch.class))).thenAnswer(i -> i.getArgument(0));
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(i -> i.getArgument(0));
        when(mapper.toResponse(any(), eq(7), eq(false))).thenReturn(
                new StockMovementResponse(ITEM_ID, 99L,
                        MovementType.ADJUSTMENT, -3, 7, null,
                        null, null, false,
                        null, null, null));
        when(availabilityService.getReserved(ITEM_ID)).thenReturn(3);

        StockMovementResponse response = stockMovementService.stocktake(request, userContext);

        assertEquals(7, response.stockAfter());
        assertEquals(-3, response.quantity());
        assertEquals(MovementType.ADJUSTMENT, response.type());
        assertEquals(7, stock.getQuantity());
        verify(stockMovementRepository).save(any());
        verify(metricService).increment("warehouse.movements.adjustment.total");
    }

    /**
     * Инвентаризация: фактический остаток меньше активных резервов.
     * Выбрасывается StocktakeConflictException.
     */
    @Test
    void stocktakeShouldThrowExceptionWhenReservedOverCounted() {
        StocktakeRequest request = new StocktakeRequest(ITEM_ID, 7);
        UserContext userContext = new UserContext(USER_ID, USERNAME);

        Item item = createItem(ITEM_ID, "Test", true, 5);

        Stock stock = new Stock();
        stock.setItem(item);
        stock.setQuantity(10);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(stockRepository.findByItemIdForUpdate(ITEM_ID)).thenReturn(Optional.of(stock));
        when(availabilityService.getReserved(ITEM_ID)).thenReturn(8);

        assertThrows(
                StocktakeConflictException.class,
                () -> stockMovementService.stocktake(request, userContext)
        );

        verify(stockRepository, never()).save(any());
        verify(stockMovementRepository, never()).save(any());

    }

    /**
     * Инвентаризация: фактический остаток БОЛЬШЕ учётного.
     * Создаётся положительное движение ADJUSTMENT, остаток увеличивается.
     */
    @Test
    void stocktakeShouldIncreaseStockWhenCountedGreater() {
        StocktakeRequest request = new StocktakeRequest(ITEM_ID, 15);
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Test", true, 5);
        Stock stock = new Stock();
        stock.setItem(item);
        stock.setQuantity(10);
        User userRef = createUserReference(USER_ID, USERNAME);

        // Создаем партию для инвентаризации
        Batch batch = createBatch(ITEM_ID, 1L, 10, LocalDateTime.now().plusDays(30));

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(stockRepository.findByItemIdForUpdate(ITEM_ID)).thenReturn(Optional.of(stock));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(batchService.findByItemIdOrderByExpiryDate(ITEM_ID)).thenReturn(List.of(batch));
        when(batchRepository.save(any(Batch.class))).thenAnswer(i -> i.getArgument(0));
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(i -> i.getArgument(0));
        when(mapper.toResponse(any(), eq(15), eq(false))).thenReturn(
                new StockMovementResponse(ITEM_ID, 99L, MovementType.ADJUSTMENT, 5,
                        15, null, null, null, false,
                        null, null, null));
        when(availabilityService.getReserved(ITEM_ID)).thenReturn(3);

        StockMovementResponse response = stockMovementService.stocktake(request, userContext);

        assertEquals(15, response.stockAfter());
        assertEquals(5, response.quantity());
        assertEquals(15, stock.getQuantity());
        verify(metricService).increment("warehouse.movements.adjustment.total");
    }

    /**
     * Инвентаризация: фактический остаток ниже minStock.
     * Устанавливается lowStockAlert=true и событие сохраняется в outbox.
     */
    @Test
    void stocktakeBelowMinStockSavesToOutbox() {
        int minStock = 10;
        StocktakeRequest request = new StocktakeRequest(ITEM_ID, 5);
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Test", true, minStock);
        Stock stock = new Stock();
        stock.setItem(item);
        stock.setQuantity(20);
        User userRef = createUserReference(USER_ID, USERNAME);

        // Создаем партию для инвентаризации
        Batch batch = createBatch(ITEM_ID, 1L, 20, LocalDateTime.now().plusDays(30));

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(stockRepository.findByItemIdForUpdate(ITEM_ID)).thenReturn(Optional.of(stock));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(batchService.findByItemIdOrderByExpiryDate(ITEM_ID)).thenReturn(List.of(batch));
        when(batchRepository.save(any(Batch.class))).thenAnswer(i -> i.getArgument(0));
        when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(
                i -> i.getArgument(0));
        when(mapper.toResponse(any(), eq(5), eq(true))).thenReturn(
                new StockMovementResponse(ITEM_ID, 99L, MovementType.ADJUSTMENT, -15, 5,
                        null, null, null, true, null,
                        null, null));
        when(availabilityService.getReserved(ITEM_ID)).thenReturn(3);

        StockMovementResponse response = stockMovementService.stocktake(request, userContext);

        assertTrue(response.lowStockAlert());
        verify(outboxService).saveLowStockAlertEvent(eventCaptor.capture());
        LowStockAlertEvent savedEvent = eventCaptor.getValue();
        assertEquals(ITEM_ID, savedEvent.itemId());
        verify(metricService).increment("warehouse.movements.adjustment.total");
    }

    /**
     * Инвентаризация: фактический остаток РАВЕН учётному.
     * Движение не создаётся, остаток не меняется.
     */
    @Test
    void stocktakeNoChangeDoesNotCreateMovement() {
        StocktakeRequest request = new StocktakeRequest(ITEM_ID, 10);
        UserContext userContext = new UserContext(USER_ID, USERNAME);
        Item item = createItem(ITEM_ID, "Test", true, 5);
        Stock stock = new Stock();
        stock.setItem(item);
        stock.setQuantity(10);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(stockRepository.findByItemIdForUpdate(ITEM_ID)).thenReturn(Optional.of(stock));
        when(mapper.toNoMovementResponse(ITEM_ID, 10)).thenReturn(
                new StockMovementResponse(ITEM_ID, null, null, 0,
                        10, null, null, null, false,
                        null, null, null));
        when(availabilityService.getReserved(ITEM_ID)).thenReturn(3);

        StockMovementResponse response = stockMovementService.stocktake(request, userContext);

        assertEquals(10, response.stockAfter());
        assertNull(response.movementId());
        verify(stockRepository, never()).save(any());
        verify(stockMovementRepository, never()).save(any());
        verify(metricService, never()).increment(any());
    }

    /**
     * Вспомогательные методы.
     *
     * @param userId   ID пользователя
     * @param username Имя пользователя
     * @return Созданный пользователь
     */
    private User createUserReference(Long userId, String username) {
        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword(PASSWORD);
        user.setRole(com.warehouse.entity.Role.ROLE_ADMIN);
        user.setActive(true);
        return user;
    }

    private Item createItem(Long itemId, String name, boolean active, int minStock) {
        Item item = new Item();
        item.setId(itemId);
        item.setName(name);
        item.setSku("SKU-" + itemId);
        item.setCategory("Тестовая категория");
        item.setActive(active);
        item.setMinStock(minStock);
        item.setPrice(BigDecimal.valueOf(100.00));
        item.setCost(BigDecimal.valueOf(50.00));
        return item;
    }

    private Batch createBatch(Long itemId, Long batchId, int quantity, LocalDateTime expiryDate) {
        Item item = new Item();
        item.setId(itemId);
        return Batch.builder()
                .id(batchId)
                .item(item)
                .quantity(quantity)
                .expiryDate(expiryDate)
                .build();
    }

}
