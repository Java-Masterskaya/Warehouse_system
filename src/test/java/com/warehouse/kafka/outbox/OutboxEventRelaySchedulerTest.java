package com.warehouse.kafka.outbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventRelaySchedulerTest {

    @Test
    void relayShouldUseClusterWideSchedulerLock() throws NoSuchMethodException {
        Method relay = OutboxEventRelay.class.getDeclaredMethod("relayPendingEvents");

        SchedulerLock schedulerLock = relay.getAnnotation(SchedulerLock.class);

        assertThat(schedulerLock).isNotNull();
        assertThat(schedulerLock.name()).isEqualTo("relayOutboxEvents");
        assertThat(schedulerLock.lockAtLeastFor()).isEmpty();
        assertThat(schedulerLock.lockAtMostFor()).isEqualTo("PT30M");
    }
}
