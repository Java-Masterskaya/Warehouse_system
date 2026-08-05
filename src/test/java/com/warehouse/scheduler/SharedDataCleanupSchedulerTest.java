package com.warehouse.scheduler;

import com.warehouse.audit.AuditService;
import com.warehouse.service.idempotency.IdempotencyServiceImpl;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SharedDataCleanupSchedulerTest {

    @Test
    void auditCleanupShouldUseClusterWideSchedulerLock() throws NoSuchMethodException {
        Method cleanup = AuditService.class.getDeclaredMethod("cleanupOldAuditLogs");

        SchedulerLock schedulerLock = cleanup.getAnnotation(SchedulerLock.class);

        assertThat(schedulerLock).isNotNull();
        assertThat(schedulerLock.name()).isEqualTo("cleanupOldAuditLogs");
        assertThat(schedulerLock.lockAtLeastFor()).isEqualTo("PT1M");
        assertThat(schedulerLock.lockAtMostFor()).isEqualTo("PT6H");
    }

    @Test
    void idempotencyCleanupShouldUseClusterWideSchedulerLock() throws NoSuchMethodException {
        Method cleanup = IdempotencyServiceImpl.class.getDeclaredMethod("cleanExpiredKeys");

        SchedulerLock schedulerLock = cleanup.getAnnotation(SchedulerLock.class);

        assertThat(schedulerLock).isNotNull();
        assertThat(schedulerLock.name()).isEqualTo("cleanExpiredIdempotencyKeys");
        assertThat(schedulerLock.lockAtLeastFor()).isEqualTo("PT1M");
        assertThat(schedulerLock.lockAtMostFor()).isEqualTo("PT30M");
    }
}
