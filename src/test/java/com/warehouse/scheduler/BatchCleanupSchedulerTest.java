package com.warehouse.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class BatchCleanupSchedulerTest {

    @Test
    void cleanupLockShouldRemainHeldLongEnoughToCoverOneSchedulerTick() throws NoSuchMethodException {
        Method cleanup = BatchCleanupScheduler.class.getDeclaredMethod("clearExpiredBatches");

        SchedulerLock schedulerLock = cleanup.getAnnotation(SchedulerLock.class);

        assertThat(schedulerLock).isNotNull();
        assertThat(schedulerLock.name()).isEqualTo("clearExpiredBatches");
        assertThat(schedulerLock.lockAtLeastFor()).isEqualTo("PT1M");
        assertThat(schedulerLock.lockAtMostFor()).isEqualTo("PT5M");
    }
}
