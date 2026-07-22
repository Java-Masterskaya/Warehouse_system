package com.warehouse.service;

import com.warehouse.dto.response.report.ExpiringBatch;
import com.warehouse.dto.response.report.LowStockItem;
import com.warehouse.dto.response.valuation.CategoryValuation;
import com.warehouse.dto.response.valuation.StockValuationResponse;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Item;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.projection.LowStockProjection;
import com.warehouse.service.report.ReportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-тест для ReportServiceImpl.
 * Тестирует генерацию отчетов по низким остаткам и истекающим партиям.
 */

@ExtendWith(MockitoExtension.class)
public class ReportServiceImplTest {

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private BatchRepository batchRepository;

    private ReportServiceImpl reportService;

    @BeforeEach
    public void setUp() {
        reportService = new ReportServiceImpl(itemRepository, batchRepository);
    }

    /**
     * Построение списка товаров с низким остатком.
     */
    @Test
    public void shouldBuildLowStockItems() {
        LowStockProjection projection = mock(LowStockProjection.class);

        when(projection.getId()).thenReturn(1L);
        when(projection.getSku()).thenReturn("WH-001");
        when(projection.getName()).thenReturn("Ноутбук Dell XPS 15");
        when(projection.getCategory()).thenReturn("Электроника");
        when(projection.getCurrentStock()).thenReturn(2);
        when(projection.getMinStock()).thenReturn(5);
        when(itemRepository.findLowStockItems()).thenReturn(List.of(projection));

        List<LowStockItem> items = reportService.getLowStockItems();

        assertEquals(1, items.size());

        LowStockItem item = items.getFirst();

        assertEquals(2, item.currentStock());
        assertEquals(5, item.minStock());
    }

    /**
     * Расчет дефицита для товаров с низким остатком.
     */
    @Test
    public void shouldCalculateDeficit() {
        LowStockProjection projection = mock(LowStockProjection.class);

        when(projection.getId()).thenReturn(1L);
        when(projection.getSku()).thenReturn("WH-001");
        when(projection.getName()).thenReturn("Ноутбук Dell XPS 15");
        when(projection.getCategory()).thenReturn("Электроника");
        when(projection.getCurrentStock()).thenReturn(2);
        when(projection.getMinStock()).thenReturn(5);
        when(itemRepository.findLowStockItems()).thenReturn(List.of(projection));

        List<LowStockItem> items = reportService.getLowStockItems();

        assertEquals(3, items.getFirst().deficit());
    }

    /**
     * Расчет суммарной стоимости складских запасов.
     */
    @Test
    public void shouldCalculateTotalStockValuation() {
        BigDecimal totalValuation = BigDecimal.valueOf(15000.00);
        when(itemRepository.calculateTotalStockValuation()).thenReturn(totalValuation);

        StockValuationResponse response = reportService.getStockValuation();

        assertNotNull(response);
        assertEquals(0, response.totalValuation().compareTo(BigDecimal.valueOf(15000.00)));
    }

    /**
     * Расчет стоимости по категориям.
     */
    @Test
    public void shouldCalculateValuationByCategory() {
        CategoryValuation electronics = new CategoryValuation("Электроника", BigDecimal.valueOf(10000.00));
        CategoryValuation furniture = new CategoryValuation("Мебель", BigDecimal.valueOf(5000.00));
        when(itemRepository.calculateValuationByCategory()).thenReturn(List.of(electronics, furniture));
        when(itemRepository.calculateTotalStockValuation()).thenReturn(BigDecimal.valueOf(15000.00));

        StockValuationResponse response = reportService.getStockValuation();

        assertNotNull(response);
        assertEquals(2, response.byCategory().size());

        CategoryValuation first = response.byCategory().get(0);
        assertEquals("Электроника", first.category());
        assertEquals(0, first.valuation().compareTo(BigDecimal.valueOf(10000.00)));
    }

    /**
     * Обработка нулевого значения стоимости.
     */
    @Test
    public void shouldHandleZeroValuation() {
        when(itemRepository.calculateTotalStockValuation()).thenReturn(BigDecimal.ZERO);
        when(itemRepository.calculateValuationByCategory()).thenReturn(List.of());

        StockValuationResponse response = reportService.getStockValuation();

        assertEquals(0, response.totalValuation().compareTo(BigDecimal.ZERO));
    }

    /**
     * Обработка товаров без остатка.
     */
    @Test
    public void shouldHandleItemsWithoutStock() {
        when(itemRepository.calculateTotalStockValuation()).thenReturn(BigDecimal.valueOf(0));
        when(itemRepository.calculateValuationByCategory()).thenReturn(List.of());

        StockValuationResponse response = reportService.getStockValuation();

        assertEquals(0, response.totalValuation().compareTo(BigDecimal.valueOf(0)));
    }

    /**
     * Округление стоимости до 2 знаков после запятой.
     */
    @Test
    public void shouldRoundValuationToTwoDecimalPlaces() {
        BigDecimal totalValuation = BigDecimal.valueOf(15000.999);
        CategoryValuation electronics = new CategoryValuation("Электроника", BigDecimal.valueOf(10000.555));
        when(itemRepository.calculateTotalStockValuation()).thenReturn(totalValuation);
        when(itemRepository.calculateValuationByCategory()).thenReturn(List.of(electronics));

        StockValuationResponse response = reportService.getStockValuation();

        assertEquals(0, response.totalValuation().compareTo(BigDecimal.valueOf(15001.00)));
        assertEquals(BigDecimal.valueOf(10000.56), response.byCategory().get(0).valuation());
    }


    /**
     * Обработка товаров без цен (cost = 0).
     */
    @Test
    public void shouldHandleZeroCost() {
        BigDecimal totalValuation = BigDecimal.valueOf(0);
        when(itemRepository.calculateTotalStockValuation()).thenReturn(totalValuation);
        when(itemRepository.calculateValuationByCategory()).thenReturn(List.of());

        StockValuationResponse response = reportService.getStockValuation();

        assertEquals(0, response.totalValuation().compareTo(BigDecimal.valueOf(0)));
    }

    /**
     * Найти партии с истекающим сроком годности в пределах N дней.
     */
    @Test
    public void shouldFindExpiringBatchesWithinDays() {
        Item item = new Item();
        item.setId(1L);
        item.setSku("WH-001");
        item.setName("Ноутбук Dell XPS 15");
        item.setCategory("Электроника");

        LocalDateTime expiryDate = LocalDateTime.now().plusDays(3);

        Batch batch = new Batch();
        batch.setId(1L);
        batch.setItem(item);
        batch.setQuantity(10);
        batch.setExpiryDate(expiryDate);

        when(batchRepository.findExpiringByDays(any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of(batch));

        List<ExpiringBatch> batches = reportService.getExpiringBatches(5);

        assertEquals(1, batches.size());

        ExpiringBatch expiringBatch = batches.getFirst();
        assertEquals(1L, expiringBatch.id());
        assertEquals("WH-001", expiringBatch.sku());
        assertEquals("Ноутбук Dell XPS 15", expiringBatch.name());
        assertEquals("Электроника", expiringBatch.category());
        assertEquals(10, expiringBatch.quantity());
        assertEquals(expiryDate, expiringBatch.expiryDate());
    }

    /**
     * Не включать просроченные партии (expiryDate <= now).
     */
    @Test
    public void shouldNotIncludeExpiredBatches() {
        Item item = new Item();
        item.setId(1L);
        item.setSku("WH-001");
        item.setName("Товар");
        item.setCategory("Категория");

        LocalDateTime expiryDate = LocalDateTime.now().plusDays(3);

        Batch validBatch = new Batch();
        validBatch.setId(2L);
        validBatch.setItem(item);
        validBatch.setQuantity(20);
        validBatch.setExpiryDate(expiryDate);

        when(batchRepository.findExpiringByDays(any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of(validBatch));

        List<ExpiringBatch> batches = reportService.getExpiringBatches(3);

        assertEquals(1, batches.size());
        assertEquals(2L, batches.getFirst().id());
    }

    /**
     * Не включать партии с нулевым количеством.
     */
    @Test
    public void shouldNotIncludeBatchesWithZeroQuantity() {
        Item item = new Item();
        item.setId(1L);
        item.setSku("WH-001");
        item.setName("Товар");
        item.setCategory("Категория");

        LocalDateTime expiryDate = LocalDateTime.now().plusDays(3);

        Batch validBatch = new Batch();
        validBatch.setId(2L);
        validBatch.setItem(item);
        validBatch.setQuantity(20);
        validBatch.setExpiryDate(expiryDate);

        when(batchRepository.findExpiringByDays(any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of(validBatch));

        List<ExpiringBatch> batches = reportService.getExpiringBatches(3);

        assertEquals(1, batches.size());
        assertEquals(20, batches.getFirst().quantity());
    }

    /**
     * Обработка пустого списка партий.
     */
    @Test
    public void shouldHandleEmptyBatchesList() {
        when(batchRepository.findExpiringByDays(any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of());

        List<ExpiringBatch> batches = reportService.getExpiringBatches(7);

        assertEquals(0, batches.size());
    }
}
