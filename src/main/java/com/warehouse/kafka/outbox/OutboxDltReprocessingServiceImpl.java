package com.warehouse.kafka.outbox;

import com.warehouse.dto.response.OutboxDltReprocessDetail;
import com.warehouse.dto.response.OutboxDltReprocessResponse;
import com.warehouse.entity.OutboxEvent;
import com.warehouse.entity.OutboxStatus;
import com.warehouse.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Реализация сервиса для повторной обработки событий из outbox DLT.
 * Восстанавливает события из DLT обратно в outbox и пытается отправить их в Kafka.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxDltReprocessingServiceImpl implements OutboxDltReprocessingService {

    private final OutboxEventRepository outboxEventRepository;

    @Async
    @Override
    public CompletableFuture<OutboxDltReprocessResponse> reprocessAllOutboxDltMessages() {
        log.info("Starting outbox DLT reprocessing");

        try {
            // Находим все события в DLT
            List<OutboxEvent> dltEvents = outboxEventRepository.findAll().stream()
                    .filter(e -> e.getStatus() == OutboxStatus.PERMANENT_FAILURE)
                    .toList();

            if (dltEvents.isEmpty()) {
                log.info("No events in outbox DLT");
                return CompletableFuture.completedFuture(
                        new OutboxDltReprocessResponse(0, 0, 0, List.of()));
            }

            log.info("Found {} events in outbox DLT", dltEvents.size());

            List<OutboxDltReprocessDetail> details = new ArrayList<>();
            int reprocessed = 0;
            int failed = 0;

            for (OutboxEvent event : dltEvents) {
                try {
                    reprocessSingleEvent(event);
                    reprocessed++;
                    details.add(OutboxDltReprocessDetail.success(event.getId(), "Successfully moved back to PENDING"));
                } catch (Exception e) {
                    failed++;
                    details.add(OutboxDltReprocessDetail.failure(event.getId(), e.getMessage()));
                    log.error("Failed to reprocess event id={}", event.getId(), e);
                }
            }

            log.info("Outbox DLT reprocessing complete: reprocessed={}, failed={}", reprocessed, failed);
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
        return outboxEventRepository.countByStatus(OutboxStatus.PERMANENT_FAILURE);
    }

    @Transactional
    private void reprocessSingleEvent(OutboxEvent event) {
        // Восстанавливаем статус в PENDING
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        event.setLastAttemptAt(null);
        event.setErrorMessage(null);
        event.setSentAt(null);
        
        outboxEventRepository.save(event);
        log.debug("Event id={} restored to PENDING status for reprocessing", event.getId());
    }
}
