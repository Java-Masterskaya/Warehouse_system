package com.warehouse.service.report;

import com.warehouse.dto.response.report.LowStockItem;
import com.warehouse.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ItemRepository itemRepository;

    @Override
    public List<LowStockItem> getLowStockItems() {
        log.debug("Get low stock report");
        return itemRepository.findLowStockItems().stream()
                .map(item -> new LowStockItem(
                        item.getId(),
                        item.getSku(),
                        item.getName(),
                        item.getCategory(),
                        item.getCurrentStock(),
                        item.getMinStock(),
                        item.getMinStock() - item.getCurrentStock())
                )
                .toList();
    }
}