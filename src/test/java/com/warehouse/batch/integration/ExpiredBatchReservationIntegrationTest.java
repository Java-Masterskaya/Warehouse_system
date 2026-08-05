package com.warehouse.batch.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.batch.BatchCleanupActor;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.reservation.ReserveRequest;
import com.warehouse.dto.response.reservation.ReservationResponse;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.entity.Warehouse;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.batch.BatchService;
import com.warehouse.service.reservation.StockReserveService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ExpiredBatchReservationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BatchService batchService;

    @Autowired
    private StockReserveService stockReserveService;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockReserveRepository reservationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Item item;
    private Warehouse warehouse;
    private UserContext userContext;

    @AfterEach
    void deleteCreatedReservations() {
        reservationRepository.deleteAllInBatch();
    }

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        warehouse = defaultWarehouse();

        User cleanupActor = userRepository.findByUsername(BatchCleanupActor.USERNAME).orElseThrow();
        assertThat(cleanupActor.isActive()).isFalse();

        Category category = categoryRepository.saveAndFlush(Category.builder()
                .name("Issue 151 category " + suffix)
                .build());
        item = itemRepository.saveAndFlush(Item.builder()
                .sku("ISSUE151-" + suffix)
                .name("Issue 151 reservation cleanup item")
                .category(category)
                .minStock(0)
                .active(true)
                .price(BigDecimal.TEN)
                .cost(BigDecimal.ONE)
                .barcode("ITEM-ISSUE151-" + suffix)
                .build());
        User user = userRepository.saveAndFlush(User.builder()
                .username("issue151-user-" + suffix)
                .password("unused-password")
                .role(Role.ROLE_ADMIN)
                .active(true)
                .build());
        userContext = new UserContext(user.getId(), user.getUsername());
    }

    @Test
    void cleanupCancelsReservationWhenItsSupportingBatchExpires() {
        LocalDateTime cleanupTime = LocalDateTime.now().withNano(0);
        Batch supportingBatch = batchService.createBatchAndIncreaseStock(
                item,
                warehouse,
                6,
                cleanupTime.plusDays(30)
        );
        ReservationResponse reservation = stockReserveService.reserve(
                item.getId(),
                new ReserveRequest(5, 30),
                userContext
        );
        int stockBeforeCleanup = stock().getQuantity();

        supportingBatch.setExpiryDate(cleanupTime.minusSeconds(1));
        batchRepository.saveAndFlush(supportingBatch);

        assertThat(batchService.clearExpiredBatches(cleanupTime)).isGreaterThanOrEqualTo(1);

        Reservation persistedReservation = reservationRepository.findById(reservation.id()).orElseThrow();
        assertThat(persistedReservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(activeReservedAt(cleanupTime)).isZero();
        assertThat(batchRepository.findById(supportingBatch.getId()).orElseThrow().getQuantity()).isZero();

        int stockAfterCleanup = stock().getQuantity();
        assertThat(stockAfterCleanup).isZero();
        assertPhysicalQuantity(stockAfterCleanup);
        assertExpiredAudit(stockBeforeCleanup - stockAfterCleanup);
    }

    @Test
    void cleanupPreservesOlderReservationAndCancelsNewerWholeReservation() {
        LocalDateTime cleanupTime = LocalDateTime.now().withNano(0);
        Batch expiredBatch = batchService.createBatchAndIncreaseStock(
                item,
                warehouse,
                5,
                cleanupTime.plusDays(5)
        );
        batchService.createBatchAndIncreaseStock(item, warehouse, 3, cleanupTime.plusDays(20));
        batchService.createBatchAndIncreaseStock(item, warehouse, 2, cleanupTime.plusDays(30));

        ReservationResponse olderReservation = stockReserveService.reserve(
                item.getId(),
                new ReserveRequest(4, 30),
                userContext
        );
        ReservationResponse newerReservation = stockReserveService.reserve(
                item.getId(),
                new ReserveRequest(4, 30),
                userContext
        );
        assertThat(olderReservation.createdAt()).isBeforeOrEqualTo(newerReservation.createdAt());
        assertThat(olderReservation.id()).isLessThan(newerReservation.id());
        int stockBeforeCleanup = stock().getQuantity();

        expiredBatch.setExpiryDate(cleanupTime.minusSeconds(1));
        batchRepository.saveAndFlush(expiredBatch);

        assertThat(batchService.clearExpiredBatches(cleanupTime)).isGreaterThanOrEqualTo(1);

        assertThat(reservationRepository.findById(olderReservation.id()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservationRepository.findById(newerReservation.id()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELED);

        int stockAfterCleanup = stock().getQuantity();
        int activeReserved = activeReservedAt(cleanupTime);
        assertThat(stockAfterCleanup).isEqualTo(5);
        assertThat(activeReserved).isEqualTo(4);
        assertThat(activeReserved).isLessThanOrEqualTo(stockAfterCleanup);
        assertThat(batchRepository.findById(expiredBatch.getId()).orElseThrow().getQuantity()).isZero();
        assertPhysicalQuantity(stockAfterCleanup);
        assertExpiredAudit(stockBeforeCleanup - stockAfterCleanup);
    }

    private Stock stock() {
        return stockRepository.findByItemIdAndWarehouseId(item.getId(), warehouse.getId()).orElseThrow();
    }

    private int activeReservedAt(LocalDateTime cleanupTime) {
        return reservationRepository.findActiveReserveSumByStock(
                stock(),
                ReservationStatus.ACTIVE,
                cleanupTime
        );
    }

    private void assertPhysicalQuantity(int expectedQuantity) {
        List<Batch> batches = batchRepository.findByItemIdAndWarehouseIdOrderByExpiryDateAsc(
                item.getId(),
                warehouse.getId()
        );
        int batchQuantity = batches.stream().mapToInt(Batch::getQuantity).sum();

        assertThat(stock().getQuantity()).isEqualTo(expectedQuantity);
        assertThat(batchQuantity).isEqualTo(expectedQuantity);
    }

    private void assertExpiredAudit(int physicallyRemoved) {
        List<Integer> quantities = jdbcTemplate.queryForList(
                """
                        SELECT quantity
                        FROM stock_movements
                        WHERE item_id = ?
                          AND warehouse_id = ?
                          AND type = 'EXPIRED'
                        ORDER BY id
                        """,
                Integer.class,
                item.getId(),
                warehouse.getId()
        );
        List<String> actors = jdbcTemplate.queryForList(
                """
                        SELECT u.username
                        FROM stock_movements sm
                        JOIN users u ON u.id = sm.user_id
                        WHERE sm.item_id = ?
                          AND sm.warehouse_id = ?
                          AND sm.type = 'EXPIRED'
                        ORDER BY sm.id
                        """,
                String.class,
                item.getId(),
                warehouse.getId()
        );

        assertThat(quantities).containsExactly(physicallyRemoved);
        assertThat(actors).containsExactly(BatchCleanupActor.USERNAME);
    }
}
