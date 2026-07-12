package com.warehouse.controller;

import com.warehouse.dto.response.report.LowStockItem;
import com.warehouse.dto.response.report.LowStockReportResponse;
import com.warehouse.dto.response.valuation.StockValuationResponse;
import com.warehouse.service.report.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
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
}