package com.warehouse.service;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.dto.response.item.WarehouseStockResponse;
import com.warehouse.dto.response.report.LowStockItem;
import com.warehouse.dto.response.valuation.StockValuationResponse;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.entity.Warehouse;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.service.item.ItemService;
import com.warehouse.service.report.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MultiWarehouseAggregationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private StockReserveRepository reservationRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ItemService itemService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private CategoryRepository categoryRepository;

    private Item item;
    private Warehouse defaultWarehouse;
    private Warehouse secondaryWarehouse;
    private String categoryName;
    private Category category;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        categoryName = "Multi Warehouse Category-" + suffix;

        category = categoryRepository.saveAndFlush(
                Category.builder()
                        .name(categoryName)
                        .build()
        );

        defaultWarehouse = warehouseRepository.findByDefaultWarehouseTrue().orElseThrow();
        secondaryWarehouse = warehouseRepository.saveAndFlush(Warehouse.builder()
                .name("Secondary Warehouse-" + suffix)
                .defaultWarehouse(false)
                .build());

        item = itemRepository.saveAndFlush(Item.builder()
                .sku("MULTI-" + suffix)
                .name("Multi warehouse item")
                .category(category)
                .minStock(20)
                .active(true)
                .price(new BigDecimal("15.00"))
                .cost(new BigDecimal("10.00"))
                .build());

        Stock defaultStock = Stock.builder()
                .item(item)
                .warehouse(defaultWarehouse)
                .quantity(10)
                .build();
        Stock secondaryStock = Stock.builder()
                .item(item)
                .warehouse(secondaryWarehouse)
                .quantity(15)
                .build();
        stockRepository.saveAllAndFlush(List.of(defaultStock, secondaryStock));
        batchRepository.saveAllAndFlush(List.of(
                Batch.builder()
                        .item(item)
                        .warehouse(defaultWarehouse)
                        .quantity(10)
                        .expiryDate(LocalDateTime.now().plusDays(30))
                        .build(),
                Batch.builder()
                        .item(item)
                        .warehouse(secondaryWarehouse)
                        .quantity(15)
                        .expiryDate(LocalDateTime.now().plusDays(30))
                        .build()
        ));

        User admin = userRepository.findByUsername("admin").orElseThrow();
        reservationRepository.saveAndFlush(Reservation.builder()
                .stock(defaultStock)
                .user(admin)
                .quantity(4)
                .status(ReservationStatus.ACTIVE)
                .expiredAt(LocalDateTime.now().plusDays(1))
                .build());
    }

    @Test
    void itemCardAggregatesStockAndReservationWithWarehouseBreakdown() {
        ItemDetailsResponse response = itemService.getItem(item.getId());

        assertThat(response.getCurrentStock()).isEqualTo(25);
        assertThat(response.getReserved()).isEqualTo(4);
        assertThat(response.getAvailable()).isEqualTo(21);
        assertThat(response.getWarehouseStocks()).hasSize(2);

        WarehouseStockResponse defaultStock = stockFor(response, defaultWarehouse.getId());
        assertThat(defaultStock.quantity()).isEqualTo(10);
        assertThat(defaultStock.reserved()).isEqualTo(4);
        assertThat(defaultStock.available()).isEqualTo(6);

        WarehouseStockResponse secondaryStock = stockFor(response, secondaryWarehouse.getId());
        assertThat(secondaryStock.quantity()).isEqualTo(15);
        assertThat(secondaryStock.reserved()).isZero();
        assertThat(secondaryStock.available()).isEqualTo(15);
    }

    @Test
    void lowStockReportUsesTotalStockAndReturnsItemOnce() {
        assertThat(reportService.getLowStockItems())
                .noneMatch(reportItem -> reportItem.id().equals(item.getId()));

        item.setMinStock(30);
        itemRepository.saveAndFlush(item);

        List<LowStockItem> matchingItems = reportService.getLowStockItems().stream()
                .filter(reportItem -> reportItem.id().equals(item.getId()))
                .toList();

        assertThat(matchingItems).singleElement().satisfies(reportItem -> {
            assertThat(reportItem.currentStock()).isEqualTo(25);
            assertThat(reportItem.minStock()).isEqualTo(30);
            assertThat(reportItem.deficit()).isEqualTo(5);
        });
    }

    @Test
    void valuationIncludesStockFromBothWarehouses() {
        StockValuationResponse response = reportService.getStockValuation();

        assertThat(response.byCategory())
                .filteredOn(categoryValuation -> categoryValuation.category().equals(categoryName))
                .singleElement()
                .satisfies(categoryValuation ->
                        assertThat(categoryValuation.valuation()).isEqualByComparingTo("250.00"));
    }

    private WarehouseStockResponse stockFor(ItemDetailsResponse response, Long warehouseId) {
        return response.getWarehouseStocks().stream()
                .filter(stock -> stock.warehouseId().equals(warehouseId))
                .findFirst()
                .orElseThrow();
    }
}
