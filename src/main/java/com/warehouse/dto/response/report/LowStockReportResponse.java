package com.warehouse.dto.response.report;

import java.time.LocalDateTime;
import java.util.List;

public record LowStockReportResponse(
        LocalDateTime generatedAt,
        int count,
        List<LowStockItemResponse> items
) {}
