package com.warehouse.service.reservation;

import com.warehouse.dto.UserContext;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.dto.request.reservation.ReservationActionRequest;
import com.warehouse.dto.request.reservation.ReserveRequest;
import com.warehouse.dto.response.reservation.ReservationResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
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
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StockReserveServiceImpl implements StockReserveService {

    StockRepository stockRepository;
    StockAvailabilityService availabilityService;
    UserRepository userRepository;
    ItemRepository itemRepository;
    StockReserveRepository stockReserveRepository;
    StockMovementService stockMovementService;
    MetricService metricService;
    StockReservationMapper mapper;
    KafkaStockAlertProducer kafkaProducer;

    @Override
    @Transactional
    @CacheEvict(value = "item", key = "#itemId")
    public ReservationResponse reserve(Long itemId, ReserveRequest request, UserContext ctx) {
        int quantity = request.quantity();

        if (quantity <= 0) {
            log.warn("Invalid quantity for reservation: itemId={}, quantity={}", itemId, quantity);
            throw new IllegalArgumentException("Quantity must be greater than 0");
        }

        Stock stock = lockStock(itemId);

        int available = availabilityService.getAvailable(itemId);
        if (available < quantity) {
            log.warn("Reservation quantity is more then now available: available = {}, quantity = {}", available,
                    quantity);
            throw InsufficientStockException.of(itemId, quantity, available);
        }

        User userRef = userRepository.getReferenceById(ctx.userId());

        Reservation reservation = stockReserveRepository.save(
                Reservation.builder().stock(stock).user(userRef).quantity(quantity).status(ReservationStatus.ACTIVE)
                        .expiredAt(LocalDateTime.now().plusDays(request.daysReserved())).build());

        metricService.increment("warehouse.reservation.reserve.total");
        return mapper.mapReservationToResponse(reservation);
    }

    @Override
    @Transactional
    @CacheEvict(value = "item", key = "#itemId")
    public ReservationResponse release(Long itemId, ReservationActionRequest request, UserContext ctx) {
        // Lock stock to serialize reservation modifications.
        lockStock(itemId);
        Reservation reservation = getActiveReservation(request.reservationId(), itemId);
        updateReservationStatus(reservation, ReservationStatus.CANCELED);
        metricService.increment("warehouse.reservation.release.total");
        return mapper.mapReservationToResponse(reservation);
    }

    @Override
    @Transactional
    @CacheEvict(value = "item", key = "#itemId")
    public ReservationResponse writeOff(Long itemId, ReservationActionRequest request, UserContext ctx) {
        // Lock stock to serialize reservation modifications.
        Stock stock = lockStock(itemId);

        Reservation reservation = getActiveReservation(request.reservationId(), itemId);
        //write-off
        int updatedRows = stockRepository.decreaseQuantityIfEnough(itemId, reservation.getQuantity());
        if (updatedRows == 0) {
            throw InsufficientStockException.of(
                    itemId,
                    reservation.getQuantity(),
                    stock.getQuantity()
            );
        }
        updateReservationStatus(reservation, ReservationStatus.CONSUMED);
        //for alert
        stock.setQuantity(stock.getQuantity() - reservation.getQuantity());

        //make movement and alert
        stockMovementService.newStockMovement(getItem(itemId), reservation.getQuantity(), ctx, MovementType.WRITE_OFF);
        sendAlert(stock, ctx);

        metricService.increment("warehouse.reservation.writeOff.total");

        return mapper.mapReservationToResponse(reservation);
    }

    @Override
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void expireReservations() {
        int updated = stockReserveRepository.expireReservations(LocalDateTime.now());

        if (updated > 0) {
            log.info("Expired {} reservations", updated);
        }
    }

    private Stock lockStock(Long itemId) {
        return stockRepository.findByItemIdForUpdate(itemId).orElseThrow(() -> {
            log.warn("Stock not found: itemId={}", itemId);
            throw EntityNotFoundException.forId("Item", itemId);
        });
    }

    private void updateReservationStatus(Reservation reservation, ReservationStatus status) {

        log.info("Update status for reservation {} to {}", reservation.getId(), status);
        reservation.setStatus(status);
        stockReserveRepository.save(reservation);
    }

    private Reservation getActiveReservation(Long reservationId, Long itemId) {
        Reservation reservation = stockReserveRepository.findById(reservationId)
                .orElseThrow(() -> EntityNotFoundException.forId("Reservation", reservationId));

        //      check reservation belong to current item
        if (!reservation.getStock().getItem().getId().equals(itemId)) {
            log.warn("The requested reservation does not match the product. Reservation is for itemId = {}, but "
                    + "current item has id = {}.", reservation.getStock().getItem().getId(), itemId);
            throw ReservationException.ofItem(reservation.getId(), itemId);
        }
        //        check status
        if (reservation.getStatus() != ReservationStatus.ACTIVE) {
            log.warn("Reservation is not active. Current status is {}", reservation.getStatus());
            throw ReservationException.ofStatus(ReservationStatus.ACTIVE, reservation.getStatus());
        }

        // check reservation expired
        if (!reservation.getExpiredAt().isAfter(LocalDateTime.now())) {
            log.warn("Reservation expired but stay Active.");
            throw ReservationException.ofStatus(ReservationStatus.EXPIRED, ReservationStatus.ACTIVE);
        }

        return reservation;
    }

    private Item getItem(long itemId) {
        return itemRepository.findById(itemId).orElseThrow(() -> EntityNotFoundException.forId("Item", itemId));
    }

    private void sendAlert(Stock stock, UserContext ctx) {
        if (stock.getQuantity() < stock.getItem().getMinStock()) {
            long itemId = stock.getItem().getId();
            String sku = stock.getItem().getSku();
            String name = stock.getItem().getName();
            int quantity = stock.getQuantity();
            int minStock = stock.getItem().getMinStock();

            LowStockAlertEvent event = new LowStockAlertEvent(itemId, sku, name, quantity, minStock, ctx.username(),
                    LocalDateTime.now());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        kafkaProducer.sendLowStockAlert(event);
                        log.info("LowStockAlert sent: itemId={}, stockAfter={}, minStock={}", itemId, quantity,
                                minStock);
                    } catch (Exception e) {
                        log.error("Failed to send LowStockAlert for itemId={}: {}", itemId, e.getMessage());
                    }
                }
            });
        }
    }
}
