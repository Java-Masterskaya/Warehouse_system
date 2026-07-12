package com.warehouse.kafka.outbox;

import com.warehouse.dto.response.OutboxDltReprocessDetail;
import com.warehouse.dto.response.OutboxDltReprocessResponse;
import com.warehouse.entity.OutboxDltEvent;
import com.warehouse.repository.OutboxDltEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
     *    и удаляется из outbox_dlt
     *
     * После репроцессинга релей (OutboxEventRelay) найдёт эти события
     * как PENDING и попытается отправить в Kafka.
     */
    @Override
    @Transactional
    public CompletableFuture<OutboxDltReprocessResponse> reprocessAllOutboxDltMessages() {
        log.info("Starting outbox DLT reprocessing");

        try {
            // Читаем из outbox_dlt (НЕ из outbox!)
            List<OutboxDltEvent> dltEvents = outboxDltEventRepository.findDltEventsForReprocess(100);

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
                    // insertFromDltToOutboxReturningId: вставляет в outbox, возвращает новый ID или null
                    Long newOutboxId = outboxDltEventRepository.insertFromDltToOutboxReturningId(dltEvent.getId());

                    if (newOutboxId != null) {
                        // deleteFromDlt: удаляет из DLT
                        outboxDltEventRepository.deleteFromDlt(dltEvent.getId());
                        
                        reprocessed++;
                        details.add(OutboxDltReprocessDetail.success(
                                dltEvent.getId(),
                                "Restored to outbox with new id=" + newOutboxId));
                        log.info("Event dlt_id={} restored to outbox as id={}",
                                dltEvent.getId(), newOutboxId);
                    } else {
                        // Событие не было вставлено (возможно, уже есть в outbox или DLT запись не найдена)
                        failed++;
                        details.add(OutboxDltReprocessDetail.failure(
                                dltEvent.getId(), "Already restored or not found"));
                        log.warn("Failed to restore dlt_id={}: already restored or not found", dltEvent.getId());
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
