package com.warehouse.service.report;

import com.warehouse.dto.response.report.ExpiringBatch;
import com.warehouse.dto.response.report.LowStockItem;
import com.warehouse.dto.response.valuation.CategoryValuation;
import com.warehouse.dto.response.valuation.StockValuationResponse;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ItemRepository itemRepository;
    private final BatchRepository batchRepository;

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

    @Transactional(readOnly = true)
    @Override
    public List<ExpiringBatch> getExpiringBatches(Integer days) {
        log.debug("Get expiring batches report: days={}", days);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime maxDate = now.plusDays(days);

        List<com.warehouse.entity.Batch> batches = batchRepository.findExpiringByDays(now, maxDate);

        log.info("Found {} expiring batches within {} days", batches.size(), days);

        return batches.stream()
                .map(batch -> new ExpiringBatch(
                        batch.getId(),
                        batch.getItem().getSku(),
                        batch.getItem().getName(),
                        batch.getItem().getCategory().getName(),
                        batch.getWarehouse().getId(),
                        batch.getWarehouse().getName(),
                        batch.getQuantity(),
                        batch.getExpiryDate()
                ))
                .toList();
    }
}
