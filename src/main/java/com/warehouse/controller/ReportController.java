package com.warehouse.controller;

import com.warehouse.dto.response.report.LowStockItem;
import com.warehouse.dto.response.report.LowStockReportResponse;
import com.warehouse.service.report.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

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
}
