package com.warehouse.kafka.outbox;

import com.warehouse.dto.response.OutboxDltReprocessResponse;
import com.warehouse.entity.OutboxStatus;
import com.warehouse.repository.OutboxEventRepository;

import java.util.concurrent.CompletableFuture;

/**
 * Сервис для повторной обработки событий из outbox DLT (Dead Letter Table).
 * Позволяет повторно попытаться отправить события, которые не удалось отправить после maxRetries попыток.
 */
public interface OutboxDltReprocessingService {
    
    /**
     * Повторно обрабатывает все сообщения из outbox DLT.
     * Возвращает CompletableFuture с результатом обработки.
     *
     * @return CompletableFuture с результатом обработки
     */
    CompletableFuture<OutboxDltReprocessResponse> reprocessAllOutboxDltMessages();

    /**
     * Возвращает количество событий в outbox DLT.
     *
     * @return количество событий в DLT
     */
    long countDltEvents();
}
