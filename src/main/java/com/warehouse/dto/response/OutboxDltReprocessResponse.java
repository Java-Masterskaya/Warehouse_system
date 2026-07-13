package com.warehouse.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Ответ на запрос повторной обработки событий из outbox DLT.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxDltReprocessResponse {
    private int totalMessages;
    private int reprocessed;
    private int failed;
    private List<OutboxDltReprocessDetail> details;
}
