package com.warehouse.cache.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.service.item.ItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

    private Long itemId;

    @BeforeEach
    void setUp() {
        stockMovementRepository.deleteAllInBatch();
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
        assertThat(response.id()).isEqualTo(itemId);
        assertThat(response.name()).isEqualTo("Ноутбук");
        assertThat(response.currentStock()).isEqualTo(10);
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
        assertThat(secondCall.name()).isEqualTo("Ноутбук");
        assertThat(secondCall.currentStock()).isEqualTo(10);
    }
}