package com.warehouse.service;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.reservation.ReservationActionRequest;
import com.warehouse.dto.request.reservation.ReserveRequest;
import com.warehouse.entity.Item;
import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.exception.ReservationException;
import com.warehouse.metric.MetricService;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.reservation.StockReserveServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class StockReserveServiceImplTest {

    @Mock
    StockRepository stockRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    StockReserveRepository stockReserveRepository;

    @Mock
    MetricService metricService;

    @InjectMocks
    StockReserveServiceImpl service;

    private UserContext ctx = new UserContext(10L, "UserName");

    /** Reserve tests */

    /**
     * Успешное резервирование остатков.
     */
    @Test
    void shouldReserveStockSuccessfully() {
        Long itemId = 1L;

        ReserveRequest request = new ReserveRequest(5, 3);

        Stock stock = new Stock();
        stock.setId(100L);
        stock.setQuantity(20);

        User user = new User();
        user.setId(10L);

        when(stockRepository.findByItemIdForUpdate(itemId)).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findSumReserveByStockAndStatus(stock, ReservationStatus.ACTIVE)).thenReturn(0L);

        when(userRepository.getReferenceById(10L)).thenReturn(user);

        service.reserve(itemId, request, ctx);

        verify(stockReserveRepository).save(any(Reservation.class));

        verify(metricService).increment("warehouse.reservation.reserve.total");
    }

    /**
     * Ели резервировать <= 0, ошибка IllegalArgumentException.
     */
    @Test
    void shouldThrowExceptionWhenQuantityIsZero() {

        ReserveRequest request = new ReserveRequest(0, 3);

        assertThrows(IllegalArgumentException.class, () -> service.reserve(1L, request, ctx));

        verifyNoInteractions(stockRepository);
        verifyNoInteractions(stockReserveRepository);
    }

    /**
     * Если товар не найден - EntityNotFoundException.
     */
    @Test
    void shouldThrowExceptionWhenStockNotFound() {

        Long itemId = 5L;

        ReserveRequest request = new ReserveRequest(2, 3);

        when(stockRepository.findByItemIdForUpdate(itemId)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.reserve(itemId, request, ctx));

        verify(stockRepository).findByItemIdForUpdate(itemId);

        verifyNoInteractions(stockReserveRepository);
    }

    /**
     * InsufficientStockException при резервировании больше доступного.
     */
    @Test
    void shouldThrowExceptionWhenNotEnoughAvailableStock() {

        Long itemId = 1L;

        ReserveRequest request = new ReserveRequest(15, 3);

        Stock stock = new Stock();
        stock.setQuantity(20);

        Reservation oldReservation = Reservation.builder().quantity(10).status(ReservationStatus.ACTIVE).build();

        when(stockRepository.findByItemIdForUpdate(itemId)).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findSumReserveByStockAndStatus(stock, ReservationStatus.ACTIVE)).thenReturn(
                (long) oldReservation.getQuantity());

        assertThrows(InsufficientStockException.class, () -> service.reserve(itemId, request, ctx));

        verify(stockReserveRepository, never()).save(any());

        verify(metricService, never()).increment(anyString());
    }

    /**
     * Подсчет доступных товаров должен учитывать уже существующие резервирования.
     */
    @Test
    void shouldConsiderExistingReservations() {

        Long itemId = 1L;

        ReserveRequest request = new ReserveRequest(11, 2);

        Stock stock = new Stock();
        stock.setQuantity(20);

        Reservation reservation1 = Reservation.builder().quantity(5).status(ReservationStatus.ACTIVE).build();

        Reservation reservation2 = Reservation.builder().quantity(5).status(ReservationStatus.ACTIVE).build();

        when(stockRepository.findByItemIdForUpdate(itemId)).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findSumReserveByStockAndStatus(stock, ReservationStatus.ACTIVE)).thenReturn(
                (long) reservation1.getQuantity() + reservation2.getQuantity());

        assertThrows(InsufficientStockException.class, () -> service.reserve(itemId, request, ctx));
    }

    /** Release tests. */

    @Test
    void shouldReleaseReservation() {
        Long itemId = 1L;
        Long reservationId = 10L;

        Stock stock = Stock.builder().build();

        Item item = Item.builder()
                .id(itemId)
                .build();

        stock.setItem(item);

        Reservation reservation = Reservation.builder()
                .id(reservationId)
                .stock(stock)
                .status(ReservationStatus.ACTIVE)
                .user(User.builder().id(ctx.userId()).build())
                .build();

        ReservationActionRequest request = new ReservationActionRequest(reservationId);

        when(stockRepository.findByItemIdForUpdate(itemId))
                .thenReturn(Optional.of(stock));
        when(stockReserveRepository.findById(reservationId))
                .thenReturn(Optional.of(reservation));

        service.release(itemId, request, ctx);

        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());

        verify(stockReserveRepository).save(reservation);
    }

    //stock not found
    @Test
    void shouldThrowWhenStockNotFound() {
        Long itemId = 1L;

        when(stockRepository.findByItemIdForUpdate(itemId))
                .thenReturn(Optional.empty());

        ReservationActionRequest request = new ReservationActionRequest(1L);

        assertThrows(
                EntityNotFoundException.class,
                () -> service.release(itemId, request, ctx)
        );

        verify(stockReserveRepository, never()).findById(any());
    }

    //reservation not found
    @Test
    void shouldThrowWhenReservationNotFound() {
        Long itemId = 1L;

        Stock stock = new Stock();

        when(stockRepository.findByItemIdForUpdate(itemId))
                .thenReturn(Optional.of(stock));

        when(stockReserveRepository.findById(5L))
                .thenReturn(Optional.empty());

        ReservationActionRequest request = new ReservationActionRequest(5L);

        assertThrows(
                EntityNotFoundException.class,
                () -> service.release(itemId, request, ctx)
        );
    }

    //reservation of another item
    @Test
    void shouldThrowWhenReservationBelongsToAnotherItem() {
        Long itemId = 1L;

        Item stockItem = Item.builder().id(itemId).build();
        Stock stock = Stock.builder().item(stockItem).build();

        Item anotherItem = Item.builder().id(2L).build();
        Stock anotherStock = Stock.builder().item(anotherItem).build();

        Reservation reservation = Reservation.builder()
                .id(10L)
                .stock(anotherStock)
                .status(ReservationStatus.ACTIVE)
                .build();

        when(stockRepository.findByItemIdForUpdate(itemId))
                .thenReturn(Optional.of(stock));

        when(stockReserveRepository.findById(10L))
                .thenReturn(Optional.of(reservation));

        ReservationActionRequest request = new ReservationActionRequest(10L);

        assertThrows(
                ReservationException.class,
                () -> service.release(itemId, request, ctx)
        );

        verify(stockReserveRepository, never()).save(any());
    }

    //reservation not active
    @Test
    void shouldThrowWhenReservationIsNotActive() {
        Long itemId = 1L;

        Item item = Item.builder().id(itemId).build();
        Stock stock = Stock.builder().item(item).build();

        Reservation reservation = Reservation.builder()
                .id(10L)
                .stock(stock)
                .status(ReservationStatus.CANCELED)
                .user(User.builder().id(ctx.userId()).build())
                .build();

        when(stockRepository.findByItemIdForUpdate(itemId))
                .thenReturn(Optional.of(stock));

        when(stockReserveRepository.findById(10L))
                .thenReturn(Optional.of(reservation));

        ReservationActionRequest request = new ReservationActionRequest(10L);

        assertThrows(
                ReservationException.class,
                () -> service.release(itemId, request, ctx)
        );

        verify(stockReserveRepository, never()).save(any());
    }

}
