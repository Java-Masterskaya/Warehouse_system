package com.warehouse.cache.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.item.ItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест для проверки кэширования карточки товара.
 */
@TestPropertySource(properties = "bucket4j.enabled=false")
@SpringBootTest
class ItemCardCacheTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private ItemService itemService;

    @Autowired
    CacheManager cacheManager;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private Long itemId;

    @BeforeEach
    void setUp() {
        cacheManager.getCache("item").clear();

        cleanDomainData();

        Category electronics = categoryRepository.save(
                Category.builder()
                        .name("Электроника")
                        .build()
        );

        Item item = new Item();
        // SKU уникален на прогон: колонка под уникальным индексом. Суффикс в barcode — на будущее.
        String itemSuffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        item.setSku("SKU-ITEMCARD-" + itemSuffix);
        item.setName("Ноутбук");
        item.setCategory(electronics);
        item.setMinStock(5);
        item.setActive(true);
        item.setPrice(BigDecimal.valueOf(1500.00));
        item.setCost(BigDecimal.valueOf(1000.00));
        item.setBarcode("ITEM-TEST-CARDCACHE-" + itemSuffix);
        itemRepository.save(item);

        Stock stock = new Stock();
        stock.setItem(item);
        stock.setWarehouse(defaultWarehouse());
        stock.setQuantity(10);
        stockRepository.save(stock);

        batchRepository.save(Batch.builder()
                .item(item)
                .warehouse(defaultWarehouse())
                .quantity(10)
                .expiryDate(LocalDateTime.now().plusDays(30))
                .build());

        itemId = item.getId();
    }

    /**
     * getItem возвращает детали товара.
     */
    @Test
    void getItemShouldReturnItemDetails() {
        ItemDetailsResponse response = itemService.getItem(itemId);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(itemId);
        assertThat(response.getName()).isEqualTo("Ноутбук");
        assertThat(response.getCurrentStock()).isEqualTo(10);
        assertThat(response.getAvailable()).isEqualTo(10);
        assertThat(response.getWarehouseStocks()).singleElement()
                .extracting(stock -> stock.available())
                .isEqualTo(10L);
    }

    @Test
    void expiredBatchIsExcludedFromAvailableQuantity() {
        Batch batch = batchRepository.findAll().get(0);
        batch.setExpiryDate(LocalDateTime.now().minusDays(1));
        batchRepository.saveAndFlush(batch);

        ItemDetailsResponse response = itemService.getItem(itemId);

        assertThat(response.getCurrentStock()).isEqualTo(10);
        assertThat(response.getAvailable()).isZero();
        assertThat(response.getWarehouseStocks()).singleElement()
                .extracting(stock -> stock.available())
                .isEqualTo(0L);
    }

    /**
     * getItem возвращает данные из кэша даже после удаления из БД.
     */
    @Test
    void getItemShouldBeCached() {
        ItemDetailsResponse firstCall = itemService.getItem(itemId);

        batchRepository.deleteAll();
        stockRepository.deleteAll();
        itemRepository.deleteAll();

        ItemDetailsResponse secondCall = itemService.getItem(itemId);

        assertThat(secondCall).isEqualTo(firstCall);
        assertThat(secondCall.getName()).isEqualTo("Ноутбук");
        assertThat(secondCall.getCurrentStock()).isEqualTo(10);
    }
}