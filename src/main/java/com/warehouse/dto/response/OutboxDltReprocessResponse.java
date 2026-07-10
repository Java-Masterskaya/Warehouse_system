package com.warehouse.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ответ на запрос повторной обработки событий из outbox DLT.
 *
 * @param totalMessages     общее количество сообщений в DLT
 * @param reprocessed       количество успешно восстановленных сообщений
 * @param failed            количество сообщений, которые не удалось восстановить
 * @param details           детали обработки каждого сообщения
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
