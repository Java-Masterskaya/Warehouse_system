package com.warehouse.service;

import com.warehouse.entity.Item;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.service.reservation.StockAvailabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class StockAvailabilityServiceTest {

    @Mock
    StockReserveRepository reservationRepository;

    @Mock
    StockRepository stockRepository;

    @Mock
    BatchRepository batchRepository;

    @InjectMocks
    StockAvailabilityService service;

    private Item item;
    private Warehouse warehouse;
    private Stock stock;

    @BeforeEach
    void setUp() {
        item = Item.builder()
                .id(10L)
                .build();

        warehouse = Warehouse.builder()
                .id(20L)
                .build();

        stock = Stock.builder()
                .id(30L)
                .item(item)
                .warehouse(warehouse)
                .quantity(10)
                .build();
    }

    @Test
    void shouldCalculateAvailableStock() {
        when(batchRepository.findNonExpiredSumByItemAndWarehouse(
                eq(item.getId()),
                eq(warehouse.getId()),
                any(LocalDateTime.class)
        )).thenReturn(8L);

        when(reservationRepository.findActiveReserveSumByStock(
                eq(stock),
                eq(ReservationStatus.ACTIVE),
                any(LocalDateTime.class)
        )).thenReturn(3);

        int available = service.getAvailable(stock);

        assertThat(available).isEqualTo(5);
    }

    @Test
    void shouldLimitAvailableQuantityByPhysicalStock() {
        when(batchRepository.findNonExpiredSumByItemAndWarehouse(
                eq(item.getId()),
                eq(warehouse.getId()),
                any(LocalDateTime.class)
        )).thenReturn(20L);

        when(reservationRepository.findActiveReserveSumByStock(
                eq(stock),
                eq(ReservationStatus.ACTIVE),
                any(LocalDateTime.class)
        )).thenReturn(3);

        int available = service.getAvailable(stock);

        assertThat(available).isEqualTo(7);
    }

    @Test
    void shouldReturnZeroWhenReservedExceedsPhysicalAvailability() {
        when(batchRepository.findNonExpiredSumByItemAndWarehouse(
                eq(item.getId()),
                eq(warehouse.getId()),
                any(LocalDateTime.class)
        )).thenReturn(4L);

        when(reservationRepository.findActiveReserveSumByStock(
                eq(stock),
                eq(ReservationStatus.ACTIVE),
                any(LocalDateTime.class)
        )).thenReturn(7);

        int available = service.getAvailable(stock);

        assertThat(available).isZero();
    }

    @Test
    void shouldGetAvailableForDefaultStock() {
        when(stockRepository.findByItemId(item.getId()))
                .thenReturn(Optional.of(stock));

        when(batchRepository.findNonExpiredSumByItemAndWarehouse(
                eq(item.getId()),
                eq(warehouse.getId()),
                any(LocalDateTime.class)
        )).thenReturn(10L);

        when(reservationRepository.findActiveReserveSumByStock(
                eq(stock),
                eq(ReservationStatus.ACTIVE),
                any(LocalDateTime.class)
        )).thenReturn(2);

        int available = service.getAvailable(item.getId());

        assertThat(available).isEqualTo(8);
        verify(stockRepository).findByItemId(item.getId());
    }

    @Test
    void shouldThrowWhenDefaultStockNotFound() {
        when(stockRepository.findByItemId(item.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAvailable(item.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void shouldCalculateTotalAvailableAcrossWarehouses() {
        Warehouse secondWarehouse = Warehouse.builder()
                .id(21L)
                .build();

        Stock secondStock = Stock.builder()
                .id(31L)
                .item(item)
                .warehouse(secondWarehouse)
                .quantity(6)
                .build();

        when(stockRepository.findAllByItemIdWithWarehouse(item.getId()))
                .thenReturn(List.of(stock, secondStock));

        when(batchRepository.findNonExpiredSumByItemAndWarehouse(
                eq(item.getId()),
                eq(warehouse.getId()),
                any(LocalDateTime.class)
        )).thenReturn(8L);

        when(batchRepository.findNonExpiredSumByItemAndWarehouse(
                eq(item.getId()),
                eq(secondWarehouse.getId()),
                any(LocalDateTime.class)
        )).thenReturn(6L);

        when(reservationRepository.findActiveReserveSumByStock(
                eq(stock),
                eq(ReservationStatus.ACTIVE),
                any(LocalDateTime.class)
        )).thenReturn(3);

        when(reservationRepository.findActiveReserveSumByStock(
                eq(secondStock),
                eq(ReservationStatus.ACTIVE),
                any(LocalDateTime.class)
        )).thenReturn(1);

        long totalAvailable = service.getTotalAvailable(item.getId());

        assertThat(totalAvailable).isEqualTo(10L);
    }
}
