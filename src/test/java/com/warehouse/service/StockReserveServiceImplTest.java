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
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.exception.ReservationException;
import com.warehouse.kafka.producer.KafkaStockAlertProducer;
import com.warehouse.mapper.StockReservationMapper;
import com.warehouse.metric.MetricService;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.movement.StockMovementService;
import com.warehouse.service.reservation.StockAvailabilityService;
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
import static org.mockito.ArgumentMatchers.anyInt;
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
    StockAvailabilityService availabilityService;

    @Mock
    StockReservationMapper mapper;

    @Mock
    StockMovementService movementService;

    @Mock
    KafkaStockAlertProducer kafkaProducer;

    @InjectMocks
    StockReserveServiceImpl service;

    private Item item;
    private Warehouse warehouse;
    private Stock stock;
    private Reservation reservation;
    private UserContext ctx;

    @BeforeEach
    void setUp() {
        item = Item.builder().id(10L).build();

        warehouse = Warehouse.builder().id(1L).name("Default Warehouse").defaultWarehouse(true).build();

        stock = Stock.builder().id(1L).quantity(10).item(item).warehouse(warehouse).build();

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

        when(availabilityService.getAvailable(stock)).thenReturn(10);

        when(userRepository.getReferenceById(ctx.userId())).thenReturn(user);

        service.reserve(item.getId(), request, ctx);

        verify(stockReserveRepository).save(any(Reservation.class));

        verify(metricService).increment("warehouse.reservation.reserve.total");
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

        when(stockRepository.findByItemIdForUpdate(item.getId())).thenReturn(Optional.of(stock));
        when(availabilityService.getAvailable(stock)).thenReturn(4);

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

        when(stockRepository.findByItemIdForUpdate(item.getId())).thenReturn(Optional.of(stock));
        when(availabilityService.getAvailable(stock)).thenReturn(4);

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

        when(stockRepository.decreaseQuantityIfEnoughAtWarehouse(
                item.getId(), warehouse.getId(), reservation.getQuantity())).thenReturn(1);
        when(stockRepository.findTotalQuantityByItemId(item.getId())).thenReturn(5L);

        service.writeOff(item.getId(), request, ctx);

        assertThat(stock.getQuantity()).isEqualTo(5);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONSUMED);

        verify(movementService).newStockMovement(
                item,
                warehouse,
                reservation.getQuantity(),
                ctx,
                MovementType.WRITE_OFF
        );

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

    @Test
    void writeOffShouldThrowWhenPhysicalStockLessThanReservationQuantity() {
        ReservationActionRequest request = new ReservationActionRequest(reservation.getId());

        stock.setQuantity(3);
        reservation.setQuantity(5);

        when(stockRepository.findByItemIdForUpdate(item.getId())).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        when(stockRepository.decreaseQuantityIfEnoughAtWarehouse(
                item.getId(), warehouse.getId(), reservation.getQuantity())).thenReturn(0);

        assertThatThrownBy(() -> service.writeOff(item.getId(), request, ctx)).isInstanceOf(
                InsufficientStockException.class);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);

        verify(movementService, never()).newStockMovement(
                any(Item.class),
                any(Warehouse.class),
                anyInt(),
                any(),
                any()
        );
    }
}
