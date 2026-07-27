package com.warehouse.service.report;

import com.warehouse.dto.response.report.ExpiringBatch;
import com.warehouse.dto.response.report.LowStockItem;
import com.warehouse.dto.response.valuation.StockValuationResponse;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ReportService {

    List<LowStockItem> getLowStockItems();

    @Transactional(readOnly = true)
    StockValuationResponse getStockValuation();

    /**
     * Найти партии, срок годности которых истекает в ближайшие N дней.
     *
     * @param days количество дней
     * @return список партий с истекающим сроком годности
     */
    List<ExpiringBatch> getExpiringBatches(Integer days);
}
