package com.warehouse.dto.response.report;

import java.time.LocalDateTime;
import java.util.List;

public record ExpiringBatchesReport(
        LocalDateTime generatedAt,
        int count,
        List<ExpiringBatch> items
) {}
