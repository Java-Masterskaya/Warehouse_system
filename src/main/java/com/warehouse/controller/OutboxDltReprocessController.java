package com.warehouse.controller;

import com.warehouse.dto.response.OutboxDltReprocessResponse;
import com.warehouse.kafka.outbox.OutboxDltReprocessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Контроллер для ручной повторной обработки событий из outbox DLT (Dead Letter Table).
 * Позволяет администратору восстановить и повторно отправить события, 
 * которые не удалось отправить после maxRetries попыток.
 */
@RestController
@RequestMapping("/api/outbox/dlt")
@RequiredArgsConstructor
@Slf4j
public class OutboxDltReprocessController {

    private final OutboxDltReprocessingService outboxDltReprocessingService;

    /**
     * Повторно обрабатывает все события из outbox DLT.
     * Синхронная операция - ждет завершения.
     *
     * @return response с результатом обработки
     */
    @PostMapping("/reprocess")
    public OutboxDltReprocessResponse reprocessAll() {
        log.info("Manual outbox DLT reprocessing triggered");
        return outboxDltReprocessingService.reprocessAllOutboxDltMessages().join();
    }

    /**
     * Проверяет количество событий в outbox DLT.
     *
     * @return количество событий в DLT
     */
    @GetMapping("/count")
    public long countDltEvents() {
        return outboxDltReprocessingService.countDltEvents();
    }
}
