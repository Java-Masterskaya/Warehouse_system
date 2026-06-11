package com.warehouse.service.report;

import com.warehouse.dto.response.report.LowStockReportResponse;

public interface ReportService {

    /**
     * Формирует отчёт по товарам с остатком ниже минимального.
     *
     * @return отчёт со списком дефицитных товаров
     */
    LowStockReportResponse getLowStockReport();
}
