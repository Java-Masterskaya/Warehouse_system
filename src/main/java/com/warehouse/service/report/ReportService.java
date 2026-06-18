package com.warehouse.service.report;

import com.warehouse.dto.response.report.LowStockItem;

import java.util.List;

public interface ReportService {

    List<LowStockItem> getLowStockItems();
}
