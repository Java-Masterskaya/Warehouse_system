package com.warehouse.service;

import com.warehouse.dto.response.report.LowStockItem;
import com.warehouse.dto.response.valuation.CategoryValuation;
import com.warehouse.dto.response.valuation.StockValuationResponse;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.projection.LowStockProjection;
import com.warehouse.service.report.ReportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-тест для ReportServiceImpl.
 * Тестирует генерацию отчетов по низким остаткам.
 */

@ExtendWith(MockitoExtension.class)
public class ReportServiceImplTest {

    @Mock
    private ItemRepository itemRepository;

    private ReportServiceImpl reportService;

    @BeforeEach
    public void setUp() {
        reportService = new ReportServiceImpl(itemRepository);
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
     * Обработка null стоимости товаров.
     */
    @Test
    public void shouldHandleNullCost() {
        BigDecimal totalValuation = BigDecimal.valueOf(5000.00);
        when(itemRepository.calculateTotalStockValuation()).thenReturn(totalValuation);
        when(itemRepository.calculateValuationByCategory()).thenReturn(List.of());

        StockValuationResponse response = reportService.getStockValuation();

        assertEquals(0, response.totalValuation().compareTo(BigDecimal.valueOf(5000.00)));
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
}
