package com.warehouse.batch.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.TransferStockRequest;
import com.warehouse.dto.request.movement.WriteOffStockRequest;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.service.batch.BatchService;
import com.warehouse.service.movement.StockMovementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
class MultiWarehouseBatchIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BatchService batchService;

    @Autowired
    private StockMovementService stockMovementService;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    private Warehouse defaultWarehouse;
    private Warehouse secondWarehouse;
    private Item item;
    private UserContext userContext;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();

        defaultWarehouse = defaultWarehouse();
        secondWarehouse = warehouseRepository.saveAndFlush(Warehouse.builder()
                .name("DOM5 second warehouse " + suffix)
                .defaultWarehouse(false)
                .build());

        Category category = categoryRepository.saveAndFlush(Category.builder()
                .name("DOM5 category " + suffix)
                .build());

        item = itemRepository.saveAndFlush(Item.builder()
                .sku("DOM5-" + suffix)
                .name("DOM5 multi-warehouse batch item")
                .category(category)
                .minStock(1)
                .price(BigDecimal.TEN)
                .cost(BigDecimal.ONE)
                .active(true)
                .build());

        User user = userRepository.saveAndFlush(User.builder()
                .username("dom5-user-" + suffix)
                .password("unused-password")
                .role(Role.ROLE_ADMIN)
                .active(true)
                .build());
        userContext = new UserContext(user.getId(), user.getUsername());
    }

    @Test
    void defaultWarehouseWriteOffUsesOnlyItsOwnBatches() {
        LocalDateTime baseTime = LocalDateTime.now().withNano(0);
        LocalDateTime secondWarehouseExpiry = baseTime.plusDays(1);
        LocalDateTime defaultEarlyExpiry = baseTime.plusDays(2);
        LocalDateTime defaultLateExpiry = baseTime.plusDays(10);

        batchService.createBatchAndIncreaseStock(item, secondWarehouse, 11, secondWarehouseExpiry);
        batchService.createBatchAndIncreaseStock(item, defaultWarehouse, 4, defaultEarlyExpiry);
        batchService.createBatchAndIncreaseStock(item, defaultWarehouse, 6, defaultLateExpiry);

        stockMovementService.writeOffReceipt(
                new WriteOffStockRequest(item.getId(), 5),
                userContext
        );

        assertThat(batchesAt(secondWarehouse))
                .extracting(Batch::getExpiryDate, Batch::getQuantity)
                .containsExactly(tuple(secondWarehouseExpiry, 11));
        assertThat(batchesAt(defaultWarehouse))
                .extracting(Batch::getExpiryDate, Batch::getQuantity)
                .containsExactly(
                        tuple(defaultEarlyExpiry, 0),
                        tuple(defaultLateExpiry, 5)
            );
        assertStocksMatchBatchTotals();

        assertThatThrownBy(() -> stockMovementService.writeOffReceipt(
                new WriteOffStockRequest(item.getId(), 6),
                userContext
        ))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("at warehouse " + defaultWarehouse.getId())
                .hasMessageContaining("available 5");

        assertThat(stockRepository.findTotalQuantityByItemId(item.getId())).isEqualTo(16);
        assertThat(batchesAt(secondWarehouse))
                .extracting(Batch::getExpiryDate, Batch::getQuantity)
                .containsExactly(tuple(secondWarehouseExpiry, 11));
        assertThat(batchesAt(defaultWarehouse))
                .extracting(Batch::getExpiryDate, Batch::getQuantity)
                .containsExactly(
                        tuple(defaultEarlyExpiry, 0),
                        tuple(defaultLateExpiry, 5)
            );
        assertStocksMatchBatchTotals();
    }

    @Test
    void transferMovesFefoSlicesAndPreservesExpiryDates() {
        LocalDateTime baseTime = LocalDateTime.now().withNano(0);
        LocalDateTime destinationExistingExpiry = baseTime.plusDays(1);
        LocalDateTime sourceEarlyExpiry = baseTime.plusDays(2);
        LocalDateTime sourceMiddleExpiry = baseTime.plusDays(5);
        LocalDateTime sourceLateExpiry = baseTime.plusDays(10);

        batchService.createBatchAndIncreaseStock(
                item,
                secondWarehouse,
                2,
                destinationExistingExpiry
        );
        batchService.createBatchAndIncreaseStock(item, defaultWarehouse, 3, sourceEarlyExpiry);
        batchService.createBatchAndIncreaseStock(item, defaultWarehouse, 5, sourceMiddleExpiry);
        batchService.createBatchAndIncreaseStock(item, defaultWarehouse, 7, sourceLateExpiry);

        stockMovementService.transfer(
                new TransferStockRequest(
                        item.getId(),
                        defaultWarehouse.getId(),
                        secondWarehouse.getId(),
                        6
                ),
                userContext
        );

        assertThat(batchesAt(defaultWarehouse))
                .extracting(Batch::getExpiryDate, Batch::getQuantity)
                .containsExactly(
                        tuple(sourceEarlyExpiry, 0),
                        tuple(sourceMiddleExpiry, 2),
                        tuple(sourceLateExpiry, 7)
            );
        assertThat(batchesAt(secondWarehouse))
                .extracting(Batch::getExpiryDate, Batch::getQuantity)
                .containsExactly(
                        tuple(destinationExistingExpiry, 2),
                        tuple(sourceEarlyExpiry, 3),
                        tuple(sourceMiddleExpiry, 3)
            );
        assertStocksMatchBatchTotals();
    }

    @Test
    void cleanupRemovesExpiredQuantityFromEachWarehouse() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Batch defaultExpired = batchService.createBatchAndIncreaseStock(
                item,
                defaultWarehouse,
                4,
                now.plusDays(1)
        );
        batchService.createBatchAndIncreaseStock(item, defaultWarehouse, 3, now.plusDays(10));
        Batch secondExpired = batchService.createBatchAndIncreaseStock(
                item,
                secondWarehouse,
                6,
                now.plusDays(2)
        );
        batchService.createBatchAndIncreaseStock(item, secondWarehouse, 5, now.plusDays(20));

        defaultExpired.setExpiryDate(now.minusDays(2));
        secondExpired.setExpiryDate(now.minusDays(1));
        batchRepository.saveAllAndFlush(List.of(defaultExpired, secondExpired));

        assertThat(batchService.clearExpiredBatches(now)).isEqualTo(2);

        assertThat(stockAt(defaultWarehouse).getQuantity()).isEqualTo(3);
        assertThat(stockAt(secondWarehouse).getQuantity()).isEqualTo(5);
        assertThat(batchRepository.findById(defaultExpired.getId()).orElseThrow().getQuantity()).isZero();
        assertThat(batchRepository.findById(secondExpired.getId()).orElseThrow().getQuantity()).isZero();
        assertStocksMatchBatchTotals();
    }

    private List<Batch> batchesAt(Warehouse warehouse) {
        return batchRepository.findByItemIdAndWarehouseIdOrderByExpiryDateAsc(
                item.getId(),
                warehouse.getId()
        );
    }

    private void assertStocksMatchBatchTotals() {
        assertStockMatchesBatchTotal(defaultWarehouse);
        assertStockMatchesBatchTotal(secondWarehouse);
    }

    private void assertStockMatchesBatchTotal(Warehouse warehouse) {
        Stock stock = stockAt(warehouse);
        int batchTotal = batchesAt(warehouse).stream()
                .mapToInt(Batch::getQuantity)
                .sum();

        assertThat(stock.getQuantity()).isEqualTo(batchTotal);
    }

    private Stock stockAt(Warehouse warehouse) {
        return stockRepository.findByItemIdAndWarehouseId(
                item.getId(),
                warehouse.getId()
        ).orElseThrow();
    }
}
