package com.warehouse.service;

import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.entity.Warehouse;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.batch.ExpiredBatchCleanupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpiredBatchCleanupServiceTest {

    private static final long ITEM_ID = 10L;
    private static final long WAREHOUSE_ID = 20L;
    private static final long ACTOR_ID = 30L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 31, 12, 0);

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private StockReserveRepository stockReserveRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @Mock
    private UserRepository userRepository;

    private ExpiredBatchCleanupService service;
    private Item item;
    private Warehouse warehouse;

    @BeforeEach
    void setUp() {
        service = new ExpiredBatchCleanupService(
                batchRepository,
                stockRepository,
                stockReserveRepository,
                stockMovementRepository,
                userRepository
        );
        item = Item.builder().id(ITEM_ID).build();
        warehouse = Warehouse.builder().id(WAREHOUSE_ID).name("Warehouse").build();
    }

    @Test
    void shouldClearScopeAndCreateOneAggregateMovement() {
        Stock stock = Stock.builder()
                .item(item)
                .warehouse(warehouse)
                .quantity(20)
                .build();
        Batch firstBatch = batch(4, NOW.minusDays(2));
        Batch secondBatch = batch(3, NOW.minusDays(1));
        List<Batch> batches = List.of(firstBatch, secondBatch);
        User actor = User.builder().id(ACTOR_ID).build();

        when(stockRepository.findByItemIdAndWarehouseIdForUpdate(ITEM_ID, WAREHOUSE_ID))
                .thenReturn(Optional.of(stock));
        when(batchRepository.findExpiredByItemAndWarehouseForUpdate(ITEM_ID, WAREHOUSE_ID, NOW))
                .thenReturn(batches);
        when(userRepository.getReferenceById(ACTOR_ID)).thenReturn(actor);

        int cleared = service.clearScope(ITEM_ID, WAREHOUSE_ID, ACTOR_ID, NOW);

        ArgumentCaptor<StockMovement> movementCaptor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).saveAndFlush(movementCaptor.capture());
        StockMovement movement = movementCaptor.getValue();
        assertThat(cleared).isEqualTo(2);
        assertThat(firstBatch.getQuantity()).isZero();
        assertThat(secondBatch.getQuantity()).isZero();
        assertThat(stock.getQuantity()).isEqualTo(13);
        assertThat(movement.getItem()).isSameAs(item);
        assertThat(movement.getWarehouse()).isSameAs(warehouse);
        assertThat(movement.getUser()).isSameAs(actor);
        assertThat(movement.getType()).isEqualTo(MovementType.EXPIRED);
        assertThat(movement.getQuantity()).isEqualTo(7);
        assertThat(movement.getCreatedAt()).isEqualTo(NOW);
        assertThat(movement.getBatch()).isNull();
        verify(batchRepository).saveAll(batches);
        verify(stockRepository).save(stock);
    }

    @Test
    void shouldCancelWholeNewestReservationAndKeepOlderReservation() {
        Stock stock = Stock.builder()
                .item(item)
                .warehouse(warehouse)
                .quantity(10)
                .build();
        Batch expiredBatch = batch(3, NOW.minusDays(1));
        Reservation olderReservation = reservation(1L, 5, NOW.minusHours(2));
        Reservation newerReservation = reservation(2L, 3, NOW.minusHours(1));
        User actor = User.builder().id(ACTOR_ID).build();

        when(stockRepository.findByItemIdAndWarehouseIdForUpdate(ITEM_ID, WAREHOUSE_ID))
                .thenReturn(Optional.of(stock));
        when(batchRepository.findExpiredByItemAndWarehouseForUpdate(ITEM_ID, WAREHOUSE_ID, NOW))
                .thenReturn(List.of(expiredBatch));
        when(stockReserveRepository.findActiveByStockForUpdate(stock, NOW))
                .thenReturn(List.of(newerReservation, olderReservation));
        when(userRepository.getReferenceById(ACTOR_ID)).thenReturn(actor);

        service.clearScope(ITEM_ID, WAREHOUSE_ID, ACTOR_ID, NOW);

        assertThat(newerReservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(olderReservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(stock.getQuantity()).isEqualTo(7);
        verify(stockReserveRepository).saveAll(List.of(newerReservation));
    }

    @Test
    void shouldKeepAllReservationsWhenTheyFitRemainingStockExactly() {
        Stock stock = Stock.builder()
                .item(item)
                .warehouse(warehouse)
                .quantity(10)
                .build();
        Batch expiredBatch = batch(2, NOW.minusDays(1));
        Reservation olderReservation = reservation(1L, 5, NOW.minusHours(2));
        Reservation newerReservation = reservation(2L, 3, NOW.minusHours(1));
        User actor = User.builder().id(ACTOR_ID).build();

        when(stockRepository.findByItemIdAndWarehouseIdForUpdate(ITEM_ID, WAREHOUSE_ID))
                .thenReturn(Optional.of(stock));
        when(batchRepository.findExpiredByItemAndWarehouseForUpdate(ITEM_ID, WAREHOUSE_ID, NOW))
                .thenReturn(List.of(expiredBatch));
        when(stockReserveRepository.findActiveByStockForUpdate(stock, NOW))
                .thenReturn(List.of(newerReservation, olderReservation));
        when(userRepository.getReferenceById(ACTOR_ID)).thenReturn(actor);

        service.clearScope(ITEM_ID, WAREHOUSE_ID, ACTOR_ID, NOW);

        assertThat(newerReservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(olderReservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        verify(stockReserveRepository, never()).saveAll(anyList());
    }

    @Test
    void shouldUseOnlyPhysicalExpiredQuantityForMovementWhenReservationIsCanceled() {
        Stock stock = Stock.builder()
                .item(item)
                .warehouse(warehouse)
                .quantity(10)
                .build();
        Batch expiredBatch = batch(2, NOW.minusDays(1));
        Reservation reservation = reservation(1L, 9, NOW.minusHours(1));
        User actor = User.builder().id(ACTOR_ID).build();

        when(stockRepository.findByItemIdAndWarehouseIdForUpdate(ITEM_ID, WAREHOUSE_ID))
                .thenReturn(Optional.of(stock));
        when(batchRepository.findExpiredByItemAndWarehouseForUpdate(ITEM_ID, WAREHOUSE_ID, NOW))
                .thenReturn(List.of(expiredBatch));
        when(stockReserveRepository.findActiveByStockForUpdate(stock, NOW))
                .thenReturn(List.of(reservation));
        when(userRepository.getReferenceById(ACTOR_ID)).thenReturn(actor);

        service.clearScope(ITEM_ID, WAREHOUSE_ID, ACTOR_ID, NOW);

        ArgumentCaptor<StockMovement> movementCaptor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).saveAndFlush(movementCaptor.capture());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(movementCaptor.getValue().getQuantity()).isEqualTo(2);
    }

    @Test
    void shouldSkipScopeWhenAnotherWorkerAlreadyClearedIt() {
        Stock stock = Stock.builder()
                .item(item)
                .warehouse(warehouse)
                .quantity(20)
                .build();
        when(stockRepository.findByItemIdAndWarehouseIdForUpdate(ITEM_ID, WAREHOUSE_ID))
                .thenReturn(Optional.of(stock));
        when(batchRepository.findExpiredByItemAndWarehouseForUpdate(ITEM_ID, WAREHOUSE_ID, NOW))
                .thenReturn(List.of());

        int cleared = service.clearScope(ITEM_ID, WAREHOUSE_ID, ACTOR_ID, NOW);

        assertThat(cleared).isZero();
        assertThat(stock.getQuantity()).isEqualTo(20);
        verify(batchRepository, never()).saveAll(List.of());
        verify(stockRepository, never()).save(stock);
        verifyNoInteractions(stockMovementRepository, userRepository);
    }

    @Test
    void shouldClearScopeWhenExpiredQuantityEqualsStockQuantity() {
        Stock stock = Stock.builder()
                .item(item)
                .warehouse(warehouse)
                .quantity(7)
                .build();
        Batch expiredBatch = batch(7, NOW.minusDays(1));
        User actor = User.builder().id(ACTOR_ID).build();

        when(stockRepository.findByItemIdAndWarehouseIdForUpdate(ITEM_ID, WAREHOUSE_ID))
                .thenReturn(Optional.of(stock));
        when(batchRepository.findExpiredByItemAndWarehouseForUpdate(ITEM_ID, WAREHOUSE_ID, NOW))
                .thenReturn(List.of(expiredBatch));
        when(userRepository.getReferenceById(ACTOR_ID)).thenReturn(actor);

        int cleared = service.clearScope(ITEM_ID, WAREHOUSE_ID, ACTOR_ID, NOW);

        assertThat(cleared).isEqualTo(1);
        assertThat(expiredBatch.getQuantity()).isZero();
        assertThat(stock.getQuantity()).isZero();

        ArgumentCaptor<StockMovement> movementCaptor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).saveAndFlush(movementCaptor.capture());
        assertThat(movementCaptor.getValue().getQuantity()).isEqualTo(7);
        verify(batchRepository).saveAll(List.of(expiredBatch));
        verify(stockRepository).save(stock);
    }

    @Test
    void shouldRejectScopeWhenExpiredQuantityExceedsStock() {
        Stock stock = Stock.builder()
                .item(item)
                .warehouse(warehouse)
                .quantity(6)
                .build();
        Batch batch = batch(7, NOW.minusDays(1));
        when(stockRepository.findByItemIdAndWarehouseIdForUpdate(ITEM_ID, WAREHOUSE_ID))
                .thenReturn(Optional.of(stock));
        when(batchRepository.findExpiredByItemAndWarehouseForUpdate(ITEM_ID, WAREHOUSE_ID, NOW))
                .thenReturn(List.of(batch));

        assertThatThrownBy(() -> service.clearScope(ITEM_ID, WAREHOUSE_ID, ACTOR_ID, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Batch quantity exceeds stock for item 10 at warehouse 20");

        assertThat(batch.getQuantity()).isEqualTo(7);
        assertThat(stock.getQuantity()).isEqualTo(6);
        verify(batchRepository, never()).saveAll(List.of(batch));
        verify(stockRepository, never()).save(stock);
        verifyNoInteractions(stockMovementRepository, userRepository);
    }

    @Test
    void shouldStartEachScopeInRequiresNewTransaction() throws NoSuchMethodException {
        Method method = ExpiredBatchCleanupService.class.getMethod(
                "clearScope",
                Long.class,
                Long.class,
                Long.class,
                LocalDateTime.class
        );

        Transactional annotation = method.getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private Batch batch(int quantity, LocalDateTime expiryDate) {
        return Batch.builder()
                .item(item)
                .warehouse(warehouse)
                .quantity(quantity)
                .expiryDate(expiryDate)
                .build();
    }

    private Reservation reservation(Long id, int quantity, LocalDateTime createdAt) {
        return Reservation.builder()
                .id(id)
                .stock(Stock.builder().item(item).warehouse(warehouse).build())
                .quantity(quantity)
                .status(ReservationStatus.ACTIVE)
                .createdAt(createdAt)
                .expiredAt(NOW.plusDays(1))
                .build();
    }
}
