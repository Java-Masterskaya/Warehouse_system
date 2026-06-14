package com.warehouse.service.report;

import com.warehouse.dto.response.report.LowStockReportResponse;

public interface ReportService {

    LowStockReportResponse getLowStockReport();
}
