package com.warehouse.service;

import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    private static final Long ITEM_ID = 1L;
    private static final Long NON_EXISTENT_ITEM_ID = 99L;

    private static final int INITIAL_STOCK_QUANTITY = 10;
    private static final int LOW_STOCK_QUANTITY = 3;
    private static final int WRITE_OFF_AMOUNT = 5;
    private static final int RECEIVE_AMOUNT = 5;
    private static final int EXCESSIVE_AMOUNT = 5;
    private static final int ZERO_AMOUNT = 0;
    private static final int NEGATIVE_AMOUNT = -5;
    private static final int EXPECTED_STOCK_AFTER_WRITE_OFF = 5;
    private static final int EXPECTED_STOCK_AFTER_RECEIVE = 15;

    private static final String COMMENT_WRITE_OFF = "Списание для заказа №123";
    private static final String COMMENT_RECEIVE = "Приход от поставщика";
    private static final String COMMENT_FIRST_RECEIPT = "Первое поступление";
    private static final String DEFAULT_COMMENT = "c";

    @Mock
    private StockRepository stockRepository;

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private StockMovementRepository movementRepository;

    @InjectMocks
    private StockService stockService;

    // ==========================================
    //         ТЕСТЫ ДЛЯ МЕТОДА writeOff
    // ==========================================

    @Test
    void writeOffSuccess() {
        // Arrange
        User user = new User();

        Item item = createActiveItem(ITEM_ID);
        Stock stock = createStock(item, INITIAL_STOCK_QUANTITY);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(stockRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));
        when(movementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        stockService.writeOff(ITEM_ID, WRITE_OFF_AMOUNT, COMMENT_WRITE_OFF, user);

        // Assert
        assertEquals(EXPECTED_STOCK_AFTER_WRITE_OFF, stock.getQuantity());

        verify(movementRepository).save(argThat(movement ->
                movement.getItem().equals(item) &&
                        movement.getUser().equals(user) &&
                        movement.getType() == MovementType.WRITE_OFF &&
                        movement.getQuantity() == WRITE_OFF_AMOUNT &&
                        COMMENT_WRITE_OFF.equals(movement.getComment())
        ));
    }

    @Test
    void writeOffInvalidAmountThrowsException() {
        User user = new User();

        assertThrows(IllegalArgumentException.class, () ->
                stockService.writeOff(ITEM_ID, ZERO_AMOUNT, DEFAULT_COMMENT, user));
        assertThrows(IllegalArgumentException.class, () ->
                stockService.writeOff(ITEM_ID, NEGATIVE_AMOUNT, DEFAULT_COMMENT, user));
        assertThrows(IllegalArgumentException.class, () ->
                stockService.writeOff(ITEM_ID, null, DEFAULT_COMMENT, user));

        verifyNoInteractions(itemRepository, stockRepository, movementRepository);
    }

    @Test
    void writeOffItemNotFoundThrowsException() {
        when(itemRepository.findById(NON_EXISTENT_ITEM_ID)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> {
            stockService.writeOff(NON_EXISTENT_ITEM_ID, WRITE_OFF_AMOUNT, DEFAULT_COMMENT, new User());
        });

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(stockRepository, never()).save(any());
        verify(movementRepository, never()).save(any());
    }

    @Test
    void writeOffItemInactiveThrowsException() {
        Item inactiveItem = createInactiveItem(ITEM_ID);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(inactiveItem));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> {
            stockService.writeOff(ITEM_ID, WRITE_OFF_AMOUNT, DEFAULT_COMMENT, new User());
        });

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(stockRepository, never()).save(any());
    }

    @Test
    void writeOffInsufficientStockThrowsException() {
        Item item = createActiveItem(ITEM_ID);
        Stock stock = createStock(item, LOW_STOCK_QUANTITY);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(stockRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(stock));

        InsufficientStockException ex = assertThrows(InsufficientStockException.class, () -> {
            stockService.writeOff(ITEM_ID, EXCESSIVE_AMOUNT, DEFAULT_COMMENT, new User());
        });

        assertEquals(LOW_STOCK_QUANTITY, stock.getQuantity());
        verify(stockRepository, never()).save(any());
        verify(movementRepository, never()).save(any());
    }

    // ==========================================
    //       ТЕСТЫ ДЛЯ МЕТОДА receive
    // ==========================================

    @Test
    void receive_Success() {
        User user = new User();

        Item item = createActiveItem(ITEM_ID);
        Stock stock = createStock(item, INITIAL_STOCK_QUANTITY);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(stockRepository.findByItemId(ITEM_ID)).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));
        when(movementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        stockService.receive(ITEM_ID, RECEIVE_AMOUNT, COMMENT_RECEIVE, user);

        // Assert
        assertEquals(EXPECTED_STOCK_AFTER_RECEIVE, stock.getQuantity());

        verify(movementRepository).save(argThat(movement ->
                movement.getType() == MovementType.RECEIVE &&
                        movement.getQuantity() == RECEIVE_AMOUNT
        ));
    }

    @Test
    void receiveWhenStockNotExistsCreatesNewStock() {
        User user = new User();
        Item item = createActiveItem(ITEM_ID);

        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(stockRepository.findByItemId(ITEM_ID)).thenReturn(Optional.empty());
        when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));
        when(movementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        stockService.receive(ITEM_ID, RECEIVE_AMOUNT, COMMENT_FIRST_RECEIPT, user);

        // Assert
        verify(stockRepository).save(argThat(stock ->
                stock.getItem().equals(item) && stock.getQuantity() == RECEIVE_AMOUNT
        ));
    }

    @Test
    void receiveInvalidAmountThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            stockService.receive(ITEM_ID, ZERO_AMOUNT, DEFAULT_COMMENT, new User());
        });
        verifyNoInteractions(itemRepository, stockRepository, movementRepository);
    }

    // ==========================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ==========================================

    private Item createActiveItem(Long itemId) {
        Item item = new Item();
        item.setId(itemId);
        item.setActive(true);
        return item;
    }

    private Item createInactiveItem(Long itemId) {
        Item item = new Item();
        item.setId(itemId);
        item.setActive(false);
        return item;
    }

    private Stock createStock(Item item, int quantity) {
        Stock stock = new Stock();
        stock.setItem(item);
        stock.setQuantity(quantity);
        return stock;
    }
}