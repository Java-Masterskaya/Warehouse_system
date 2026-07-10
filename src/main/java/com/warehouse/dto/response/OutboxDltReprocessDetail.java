package com.warehouse.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Детали обработки одного сообщения из outbox DLT.
 *
 * @param outboxId     ID события в outbox
 * @param success      успешна ли операция
 * @param message      сообщение (успех или ошибка)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxDltReprocessDetail {
    private Long outboxId;
    private boolean success;
    private String message;

    public static OutboxDltReprocessDetail success(Long outboxId, String message) {
        return OutboxDltReprocessDetail.builder()
                .outboxId(outboxId)
                .success(true)
                .message(message)
                .build();
    }

    public static OutboxDltReprocessDetail failure(Long outboxId, String message) {
        return OutboxDltReprocessDetail.builder()
                .outboxId(outboxId)
                .success(false)
                .message(message)
                .build();
    }
}
