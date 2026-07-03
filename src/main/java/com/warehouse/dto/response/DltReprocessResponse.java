package com.warehouse.dto.response;

import java.util.List;

public record DltReprocessResponse(
        int totalMessages,
        int successfullyReprocessed,
        int failed,
        List<DltReprocessDetail> details
) {
    public static DltReprocessResponse empty() {
        return new DltReprocessResponse(0, 0, 0, List.of());
    }
}
