package com.warehouse.service;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.reservation.ReservationActionRequest;
import com.warehouse.dto.request.reservation.ReserveRequest;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.exception.ReservationException;
import com.warehouse.mapper.StockReservationMapper;
import com.warehouse.metric.MetricService;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.movement.StockMovementService;
import com.warehouse.service.reservation.StockReserveServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    ItemRepository itemRepository;

    @Mock
    StockReserveRepository stockReserveRepository;

    @Mock
    MetricService metricService;

    @Mock
    StockReservationMapper mapper;

    @Mock
    StockMovementService movementService;

    @InjectMocks
    StockReserveServiceImpl service;

    private Item item;
    private Stock stock;
    private Reservation reservation;
    private UserContext ctx;

    @BeforeEach
    void setUp() {
        item = Item.builder().id(10L).build();

        stock = Stock.builder().id(1L).quantity(10).item(item).build();

        reservation = Reservation.builder().id(5L).stock(stock).quantity(5).status(ReservationStatus.ACTIVE)
                .expiredAt(LocalDateTime.now().plusDays(1)).build();

        ctx = new UserContext(1L, "Username");
    }

    /** Reserve tests */
    /**
     * Успешное резервирование остатков.
     */
    @Test
    void shouldReserveStockSuccessfully() {
        ReserveRequest request = new ReserveRequest(5, 3);

        User user = new User();
        user.setId(ctx.userId());

        when(stockRepository.findByItemIdForUpdate(item.getId())).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findSumReserveByStockAndStatus(stock, ReservationStatus.ACTIVE)).thenReturn(0L);

        when(userRepository.getReferenceById(ctx.userId())).thenReturn(user);

        service.reserve(item.getId(), request, ctx);

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
        ReserveRequest request = new ReserveRequest(5, 3);

        Reservation oldReservation = Reservation.builder().quantity(10).status(ReservationStatus.ACTIVE).build();

        when(stockRepository.findByItemIdForUpdate(item.getId())).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findSumReserveByStockAndStatus(stock, ReservationStatus.ACTIVE)).thenReturn(
                (long) oldReservation.getQuantity());

        assertThrows(InsufficientStockException.class, () -> service.reserve(item.getId(), request, ctx));

        verify(stockReserveRepository, never()).save(any());

        verify(metricService, never()).increment(anyString());
    }

    /**
     * Подсчет доступных товаров должен учитывать уже существующие резервирования.
     */
    @Test
    void shouldConsiderExistingReservations() {
        ReserveRequest request = new ReserveRequest(11, 2);

        Reservation reservation1 = Reservation.builder().quantity(6).status(ReservationStatus.ACTIVE).build();

        when(stockRepository.findByItemIdForUpdate(item.getId())).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findSumReserveByStockAndStatus(stock, ReservationStatus.ACTIVE)).thenReturn(
                (long) reservation.getQuantity() + reservation1.getQuantity());

        assertThrows(InsufficientStockException.class, () -> service.reserve(item.getId(), request, ctx));
    }

    /**
     * Release tests.
     */

    @Test
    void shouldReleaseReservation() {
        stock.setItem(item);

        ReservationActionRequest request = new ReservationActionRequest(reservation.getId());

        when(stockRepository.findByItemIdForUpdate(item.getId())).thenReturn(Optional.of(stock));
        when(stockReserveRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        service.release(item.getId(), request, ctx);

        assertEquals(ReservationStatus.CANCELED, reservation.getStatus());

        verify(stockReserveRepository).save(reservation);
    }

    //stock not found
    @Test
    void shouldThrowWhenStockNotFound() {
        Long itemId = 1L;

        when(stockRepository.findByItemIdForUpdate(itemId)).thenReturn(Optional.empty());

        ReservationActionRequest request = new ReservationActionRequest(1L);

        assertThrows(EntityNotFoundException.class, () -> service.release(itemId, request, ctx));

        verify(stockReserveRepository, never()).findById(any());
    }

    //reservation not found
    @Test
    void shouldThrowWhenReservationNotFound() {
        Long itemId = 1L;

        when(stockRepository.findByItemIdForUpdate(itemId)).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findById(5L)).thenReturn(Optional.empty());

        ReservationActionRequest request = new ReservationActionRequest(5L);

        assertThrows(EntityNotFoundException.class, () -> service.release(itemId, request, ctx));
    }

    //reservation of another item
    @Test
    void shouldThrowWhenReservationBelongsToAnotherItem() {
        Item anotherItem = Item.builder().id(2L).build();
        Stock anotherStock = Stock.builder().item(anotherItem).build();

        Reservation reservation2 = Reservation.builder().id(10L).stock(anotherStock).status(ReservationStatus.ACTIVE)
                .build();

        when(stockRepository.findByItemIdForUpdate(item.getId())).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findById(10L)).thenReturn(Optional.of(reservation2));

        ReservationActionRequest request = new ReservationActionRequest(10L);

        assertThrows(ReservationException.class, () -> service.release(item.getId(), request, ctx));

        verify(stockReserveRepository, never()).save(any());
    }

    //reservation not active
    @Test
    void shouldThrowWhenReservationIsNotActive() {
        Reservation reservation2 = Reservation.builder().id(10L).stock(stock).status(ReservationStatus.CANCELED)
                .user(User.builder().id(ctx.userId()).build()).build();

        when(stockRepository.findByItemIdForUpdate(item.getId())).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findById(10L)).thenReturn(Optional.of(reservation2));

        ReservationActionRequest request = new ReservationActionRequest(10L);

        assertThrows(ReservationException.class, () -> service.release(item.getId(), request, ctx));

        verify(stockReserveRepository, never()).save(any());
    }

    // write-off
    //success
    @Test
    void writeOffShouldConsumeReservationAndCreateMovement() {

        ReservationActionRequest request = new ReservationActionRequest(reservation.getId());

        when(stockRepository.findByItemIdForUpdate(item.getId())).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        service.writeOff(item.getId(), request, ctx);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONSUMED);

        verify(movementService).newStockMovement(item, reservation.getQuantity(), ctx, MovementType.WRITE_OFF);

        verify(stockReserveRepository).save(reservation);

        verify(metricService).increment("warehouse.reservation.writeOff.total");
    }

    //reservation not found
    @Test
    void writeOffShouldThrowWhenReservationNotFound() {

        when(stockRepository.findByItemIdForUpdate(10L)).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.writeOff(10L, new ReservationActionRequest(5L), ctx)).isInstanceOf(
                EntityNotFoundException.class);

        verifyNoInteractions(movementService);
    }

    //when itemId != reservation.getItem().getId()
    @Test
    void writeOffShouldThrowWhenReservationBelongsToAnotherItem() {

        Stock anotherStock = Stock.builder().item(Item.builder().id(99L).build()).build();

        reservation.setStock(anotherStock);

        when(stockRepository.findByItemIdForUpdate(10L)).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findById(5L)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.writeOff(10L, new ReservationActionRequest(5L), ctx)).isInstanceOf(
                ReservationException.class);

        verifyNoInteractions(movementService);
    }

    //Reservation status is not active
    @Test
    void writeOffShouldThrowWhenReservationNotActive() {

        reservation.setStatus(ReservationStatus.CANCELED);

        when(stockRepository.findByItemIdForUpdate(10L)).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findById(5L)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.writeOff(10L, new ReservationActionRequest(5L), ctx)).isInstanceOf(
                ReservationException.class);

        verifyNoInteractions(movementService);
    }

    // reservation expiredAt < LocalDateTime.now() but reservation is Active
    @Test
    void writeOffShouldThrowWhenReservationExpired() {

        reservation.setExpiredAt(LocalDateTime.now().minusDays(1));

        when(stockRepository.findByItemIdForUpdate(10L)).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findById(5L)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.writeOff(10L, new ReservationActionRequest(5L), ctx)).isInstanceOf(
                ReservationException.class);

        verifyNoInteractions(movementService);
    }
}
