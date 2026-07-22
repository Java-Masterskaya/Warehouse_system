package com.warehouse.cache.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.item.ItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест для проверки кэширования карточки товара.
 */
class ItemCardCacheTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ItemService itemService;

    @Autowired
    CacheManager cacheManager;
    @Autowired
    private BatchRepository batchRepository;

    private Long itemId;

    @BeforeEach
    void setUp() {
        cacheManager.getCache("item").clear();

        stockMovementRepository.deleteAllInBatch();
        batchRepository.deleteAll();
        stockRepository.deleteAllInBatch();
        itemRepository.deleteAllInBatch();

        Item item = new Item();
        item.setSku("SKU-001");
        item.setName("Ноутбук");
        item.setCategory("Электроника");
        item.setMinStock(5);
        item.setActive(true);
        item.setPrice(BigDecimal.valueOf(1500.00));
        item.setCost(BigDecimal.valueOf(1000.00));
        itemRepository.save(item);

        Stock stock = new Stock();
        stock.setItem(item);
        stock.setQuantity(10);
        stockRepository.save(stock);

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
    }

    /**
     * getItem возвращает данные из кэша даже после удаления из БД.
     */
    @Test
    void getItemShouldBeCached() {
        ItemDetailsResponse firstCall = itemService.getItem(itemId);

        stockRepository.deleteAll();
        itemRepository.deleteAll();

        ItemDetailsResponse secondCall = itemService.getItem(itemId);

        assertThat(secondCall).isEqualTo(firstCall);
        assertThat(secondCall.getName()).isEqualTo("Ноутбук");
        assertThat(secondCall.getCurrentStock()).isEqualTo(10);
    }
}