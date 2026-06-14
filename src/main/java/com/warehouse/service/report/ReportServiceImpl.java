package com.warehouse.service.report;

import com.warehouse.dto.response.report.LowStockItemResponse;
import com.warehouse.dto.response.report.LowStockReportResponse;
import com.warehouse.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ItemRepository itemRepository;

    @Override
    public LowStockReportResponse getLowStockReport() {
        log.debug("Get low stock report");
        List<LowStockItemResponse> items = itemRepository.findLowStockItems().stream()
                .map(item -> new LowStockItemResponse(
                        item.getId(),
                        item.getSku(),
                        item.getName(),
                        item.getCategory(),
                        item.getCurrentStock(),
                        item.getMinStock(),
                        item.getMinStock() - item.getCurrentStock())
                )
                .toList();

        return new LowStockReportResponse(LocalDateTime.now(), items.size(), items);
    }
}
