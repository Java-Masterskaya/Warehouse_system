package com.warehouse.scheduler;

import com.warehouse.service.batch.BatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Планировщик задач для очистки просроченных партий.
 * Выполняет регулярную очистку партий с истекшим сроком годности.
 * Безопасен для кластерного развертывания благодаря ShedLock.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BatchCleanupScheduler {

    private final BatchService batchService;

    /**
     * Ежедневно в 03:00 утра проверяет и очищает протухшие партии.
     * Использует pessimistic locking для безопасного обновления.
     * Защищен от двойного запуска в кластере ShedLock.
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @SchedulerLock(name = "clearExpiredBatches", lockAtMostFor = "PT5M")
    public void clearExpiredBatches() {
        log.debug("Scheduled task: clearing expired batches");
        int cleared = batchService.clearExpiredBatches(java.time.LocalDateTime.now());
        log.info("Scheduled batch cleanup completed: cleared {} expired batches", cleared);
    }
}
