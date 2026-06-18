package com.warehouse.service;

import com.warehouse.dto.response.report.LowStockItem;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.projection.LowStockProjection;
import com.warehouse.service.report.ReportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты для ReportServiceImpl: отчеты по товарам.
 * Проверяют: формирование списка товаров с низким остатком, расчет дефицита.
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
     * Формирование списка товаров с низким остатком.
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
}
