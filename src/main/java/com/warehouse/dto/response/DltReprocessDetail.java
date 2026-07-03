package com.warehouse.dto.response;

import java.time.LocalDateTime;

public record DltReprocessDetail(
        String originalKey,
        LocalDateTime originalTimestamp,
        String exceptionMessage,
        boolean success,
        String errorMessage
) {
}
