package com.warehouse.service;

import com.warehouse.dto.response.report.LowStockItemResponse;
import com.warehouse.dto.response.report.LowStockReportResponse;
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

@ExtendWith(MockitoExtension.class)
public class ReportServiceImplTest {

    @Mock
    private ItemRepository itemRepository;

    private ReportServiceImpl reportService;

    @BeforeEach
    public void setUp() {
        reportService = new ReportServiceImpl(itemRepository);
    }

    @Test
    public void shouldBuildLowStockItems() {
        // 1. Подготовка данных
        LowStockProjection projection = mock(LowStockProjection.class);

        // 2. Настройка моков
        when(projection.getId()).thenReturn(1L);
        when(projection.getSku()).thenReturn("WH-001");
        when(projection.getName()).thenReturn("Ноутбук Dell XPS 15");
        when(projection.getCategory()).thenReturn("Электроника");
        when(projection.getCurrentStock()).thenReturn(2);
        when(projection.getMinStock()).thenReturn(5);
        when(itemRepository.findLowStockItems()).thenReturn(List.of(projection));

        // 3. Выполнение
        LowStockReportResponse response = reportService.getLowStockReport();

        // 4. Проверки
        assertEquals(1, response.count());

        LowStockItemResponse item = response.items().getFirst();

        assertEquals(2, item.currentStock());
        assertEquals(5, item.minStock());
    }

    @Test
    public void shouldCalculateDeficit() {
        // 1. Подготовка данных
        LowStockProjection projection = mock(LowStockProjection.class);

        // 2. Настройка моков
        when(projection.getId()).thenReturn(1L);
        when(projection.getSku()).thenReturn("WH-001");
        when(projection.getName()).thenReturn("Ноутбук Dell XPS 15");
        when(projection.getCategory()).thenReturn("Электроника");
        when(projection.getCurrentStock()).thenReturn(2);
        when(projection.getMinStock()).thenReturn(5);
        when(itemRepository.findLowStockItems()).thenReturn(List.of(projection));

        // 3. Выполнение
        LowStockReportResponse response = reportService.getLowStockReport();

        // 4. Проверки
        assertEquals(3, response.items().getFirst().deficit());
    }
}
