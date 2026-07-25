package com.warehouse.controller;

import com.warehouse.dto.response.report.ExpiringBatch;
import com.warehouse.dto.response.report.ExpiringBatchesReport;
import com.warehouse.dto.response.report.LowStockItem;
import com.warehouse.dto.response.report.LowStockReportResponse;
import com.warehouse.dto.response.valuation.StockValuationResponse;
import com.warehouse.service.report.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Validated
@Tag(name = "Отчёты", description = "Аналитические отчёты (только ADMIN)")
@SecurityRequirement(name = "bearerAuth")
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "Товары ниже минимального остатка")
    @GetMapping("/low-stock")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public LowStockReportResponse getLowStock() {
        List<LowStockItem> items = reportService.getLowStockItems();
        return new LowStockReportResponse(LocalDateTime.now(), items.size(), items);
    }

    @Operation(summary = "Оценка складских запасов",
            description = "Σ quantity × cost с разрезом по категориям. Доступно только ADMIN.")
    @GetMapping("/stock-valuation")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public StockValuationResponse getStockValuation() {
        log.debug("Received stock valuation request");
        return reportService.getStockValuation();
    }

    @Operation(summary = "Партии с истекающим сроком годности",
            description = "Найти партии, срок годности которых истекает в ближайшие N дней. FEFO:"
                   + " first-expire-first-out. Доступно только ADMIN.")
    @GetMapping("/expiring")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public ExpiringBatchesReport getExpiringBatches(
            @RequestParam
            @Min(0)
            @Max(365)
            int days
    ) {
        log.debug("Received expiring batches request: days={}", days);
        List<ExpiringBatch> batches = reportService.getExpiringBatches(days);
        return new ExpiringBatchesReport(LocalDateTime.now(), batches.size(), batches);
    }
}
