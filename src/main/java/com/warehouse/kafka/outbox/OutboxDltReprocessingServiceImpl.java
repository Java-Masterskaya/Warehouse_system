package com.warehouse.kafka.outbox;

import com.warehouse.dto.response.OutboxDltReprocessDetail;
import com.warehouse.dto.response.OutboxDltReprocessResponse;
import com.warehouse.entity.OutboxDltEvent;
import com.warehouse.repository.OutboxDltEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Реализация сервиса для повторной обработки событий из outbox DLT.
 *
 * DLT (Dead Letter Table) — таблица outbox_dlt, куда попадают события,
 * которые превысили лимит ретраев или имеют битый payload.
 *
 * Репроцессинг: перемещает события из outbox_dlt обратно в outbox
 * с PENDING статусом, чтобы релей попытался отправить их снова.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxDltReprocessingServiceImpl implements OutboxDltReprocessingService {

    private final OutboxDltEventRepository outboxDltEventRepository;

    /**
     * Перемещает ВСЕ события из DLT обратно в outbox для повторной обработки.
     *
     * Каждое событие:
     * 1. Читается из outbox_dlt
     * 2. Атомарно: вставляется в outbox как новая запись со статусом PENDING
     *    и удаляется из outbox_dlt (через CTE в restoreToOutbox)
     *
     * После репроцессинга релей (OutboxEventRelay) найдёт эти события
     * как PENDING и попытается отправить в Kafka.
     */
    @Async
    @Override
    @Transactional
    public CompletableFuture<OutboxDltReprocessResponse> reprocessAllOutboxDltMessages() {
        log.info("Starting outbox DLT reprocessing");

        try {
            // Читаем из outbox_dlt (НЕ из outbox!)
            List<OutboxDltEvent> dltEvents = outboxDltEventRepository.findAllByOrderByDltCreatedAtDesc();

            if (dltEvents.isEmpty()) {
                log.info("No events in outbox DLT");
                return CompletableFuture.completedFuture(
                        new OutboxDltReprocessResponse(0, 0, 0, List.of()));
            }

            log.info("Found {} events in outbox DLT", dltEvents.size());

            List<OutboxDltReprocessDetail> details = new ArrayList<>();
            int reprocessed = 0;
            int failed = 0;

            for (OutboxDltEvent dltEvent : dltEvents) {
                try {
                    // restoreToOutbox: атомарно читает из DLT, вставляет в outbox, удаляет из DLT
                    Long newOutboxId = outboxDltEventRepository.restoreToOutbox(dltEvent.getId());

                    if (newOutboxId != null) {
                        reprocessed++;
                        details.add(OutboxDltReprocessDetail.success(
                                dltEvent.getId(),
                                "Restored to outbox with new id=" + newOutboxId));
                        log.info("Event dlt_id={} restored to outbox as id={}",
                                dltEvent.getId(), newOutboxId);
                    } else {
                        failed++;
                        details.add(OutboxDltReprocessDetail.failure(
                                dltEvent.getId(), "Not found in DLT or already restored"));
                        log.warn("Failed to restore dlt_id={}: not found", dltEvent.getId());
                    }
                } catch (Exception e) {
                    failed++;
                    details.add(OutboxDltReprocessDetail.failure(dltEvent.getId(), e.getMessage()));
                    log.error("Failed to restore dlt_id={}", dltEvent.getId(), e);
                }
            }

            log.info("Outbox DLT reprocessing complete: total={}, reprocessed={}, failed={}",
                    dltEvents.size(), reprocessed, failed);
            return CompletableFuture.completedFuture(
                    new OutboxDltReprocessResponse(dltEvents.size(), reprocessed, failed, details));

        } catch (Exception e) {
            log.error("Outbox DLT reprocessing error", e);
            return CompletableFuture.completedFuture(
                    new OutboxDltReprocessResponse(0, 0, 0, List.of()));
        }
    }

    @Override
    public long countDltEvents() {
        return outboxDltEventRepository.count();
    }
}
