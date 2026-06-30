package com.warehouse.service.report;

import com.warehouse.dto.response.report.LowStockItem;
import com.warehouse.dto.response.valuation.CategoryValuation;
import com.warehouse.dto.response.valuation.StockValuationResponse;
import com.warehouse.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    @Transactional(readOnly = true)
    @Override
    public StockValuationResponse getStockValuation() {
        log.debug("Calculating stock valuation");

        BigDecimal total = itemRepository.calculateTotalStockValuation();
        var byCategory = itemRepository.calculateValuationByCategory();

        // Округляем total до 2 знаков
        BigDecimal roundedTotal = total.setScale(2, RoundingMode.HALF_UP);

        // Округляем каждую категорию
        var roundedByCategory = byCategory.stream()
                .map(cat -> new CategoryValuation(
                        cat.category(),
                        cat.valuation().setScale(2, RoundingMode.HALF_UP)
                ))
                .toList();

        log.info("Stock valuation calculated: total={}, categories={}",
                roundedTotal, roundedByCategory.size());

        return new StockValuationResponse(roundedTotal, roundedByCategory);
    }


}