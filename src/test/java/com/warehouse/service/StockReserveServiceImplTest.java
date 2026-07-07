package com.warehouse.service;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.reservation.ReserveRequest;
import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.InsufficientStockException;
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

    /**
     * Успешное резервирование остатков.
     */
    @Test
    void shouldReserveStockSuccessfully() {
        Long itemId = 1L;

        ReserveRequest request = new ReserveRequest(5, 3);

        UserContext ctx = new UserContext(10L, "UserName");

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

        UserContext ctx = new UserContext(1L, "Username");

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

        UserContext ctx = new UserContext(1L, "Username");

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

        UserContext ctx = new UserContext(1L, "Username");

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

        UserContext ctx = new UserContext(1L, "Username");

        Stock stock = new Stock();
        stock.setQuantity(20);

        Reservation reservation1 = Reservation.builder().quantity(5).status(ReservationStatus.ACTIVE).build();

        Reservation reservation2 = Reservation.builder().quantity(5).status(ReservationStatus.ACTIVE).build();

        when(stockRepository.findByItemIdForUpdate(itemId)).thenReturn(Optional.of(stock));

        when(stockReserveRepository.findSumReserveByStockAndStatus(stock, ReservationStatus.ACTIVE)).thenReturn(
                (long) reservation1.getQuantity() + reservation2.getQuantity());

        assertThrows(InsufficientStockException.class, () -> service.reserve(itemId, request, ctx));
    }

}
