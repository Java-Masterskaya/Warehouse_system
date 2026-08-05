package com.warehouse.kafka.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.response.DltReprocessResponse;
import com.warehouse.exception.DltReprocessingInProgressException;
import com.warehouse.kafka.config.KafkaTopicProperties;
import com.warehouse.lock.DistributedLock;
import com.warehouse.lock.PostgresAdvisoryLockManager;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DltReprocessingServiceLockTest {

    private static final String DLT_TOPIC_NAME = "low-stock-alerts";
    private static final String LOCK_NAME = "kafka-dlt-low-stock-reprocess";

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private KafkaTopicProperties topicProperties;

    @Mock
    private PostgresAdvisoryLockManager advisoryLockManager;

    @Mock
    private DistributedLock distributedLock;

    @Mock
    private AsyncTaskExecutor applicationTaskExecutor;

    @Mock
    private Consumer<String, String> consumer;

    private DltReprocessingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new DltReprocessingServiceImpl(
                kafkaTemplate,
                topicProperties,
                new ObjectMapper(),
                advisoryLockManager,
                applicationTaskExecutor
        ));
    }

    @Test
    void shouldAcquireBeforeSchedulingAndRejectConcurrentStart() {
        when(advisoryLockManager.tryAcquire(LOCK_NAME))
                .thenReturn(Optional.of(distributedLock), Optional.empty());

        CompletableFuture<DltReprocessResponse> first = service.reprocessAllDltMessages();

        assertThat(first).isNotDone();
        assertThatThrownBy(service::reprocessAllDltMessages)
                .isInstanceOf(DltReprocessingInProgressException.class)
                .hasMessage("DLT reprocessing is already running");
        verify(advisoryLockManager, times(2)).tryAcquire(LOCK_NAME);
        verify(applicationTaskExecutor, times(1))
                .execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        verify(consumer, never()).assign(org.mockito.ArgumentMatchers.anyList());
        verify(distributedLock, never()).close();
    }

    @Test
    void shouldReleaseLockAfterWorkerFinishesAndBeforeFutureCompletes() {
        when(advisoryLockManager.tryAcquire(LOCK_NAME))
                .thenReturn(Optional.of(distributedLock));
        when(topicProperties.getName()).thenReturn(DLT_TOPIC_NAME);
        doReturn(consumer).when(service).createDltConsumer();
        when(consumer.partitionsFor(anyString())).thenReturn(List.of());
        when(consumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        ReflectionTestUtils.setField(service, "reprocessBatchSize", 1);
        ArgumentCaptor<Runnable> worker = ArgumentCaptor.forClass(Runnable.class);

        CompletableFuture<DltReprocessResponse> response = service.reprocessAllDltMessages();
        verify(applicationTaskExecutor).execute(worker.capture());
        doAnswer(invocation -> {
            assertThat(response).isNotDone();
            return null;
        }).when(distributedLock).close();

        worker.getValue().run();

        assertThat(response).isCompletedWithValueMatching(result ->
                result.totalMessages() == 0
                        && result.successfullyReprocessed() == 0
                        && result.failed() == 0);
        verify(consumer).assign(List.of());
        verify(consumer, times(3)).poll(any(Duration.class));
        verify(consumer).close();
        verify(distributedLock).close();
    }

    @Test
    void shouldReleaseLockWhenExecutorRejectsWorker() {
        RuntimeException schedulingFailure = new RuntimeException("Executor rejected worker");
        when(advisoryLockManager.tryAcquire(LOCK_NAME))
                .thenReturn(Optional.of(distributedLock));
        doThrow(schedulingFailure).when(applicationTaskExecutor)
                .execute(org.mockito.ArgumentMatchers.any(Runnable.class));

        assertThatThrownBy(service::reprocessAllDltMessages)
                .isSameAs(schedulingFailure);
        verify(distributedLock).close();
        verify(consumer, never()).assign(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void shouldCountFailedRecordsTowardBatchLimit() {
        when(advisoryLockManager.tryAcquire(LOCK_NAME))
                .thenReturn(Optional.of(distributedLock));
        when(topicProperties.getName()).thenReturn(DLT_TOPIC_NAME);
        doReturn(consumer).when(service).createDltConsumer();
        when(consumer.partitionsFor(anyString())).thenReturn(List.of());
        TopicPartition topicPartition = new TopicPartition(DLT_TOPIC_NAME + ".DLT", 0);
        List<ConsumerRecord<String, String>> failedRecords = List.of(
                invalidRecord(topicPartition, 0),
                invalidRecord(topicPartition, 1),
                invalidRecord(topicPartition, 2)
        );
        when(consumer.poll(any(Duration.class)))
                .thenReturn(new ConsumerRecords<>(Map.of(topicPartition, failedRecords)));
        ReflectionTestUtils.setField(service, "reprocessBatchSize", 2);
        ArgumentCaptor<Runnable> worker = ArgumentCaptor.forClass(Runnable.class);

        CompletableFuture<DltReprocessResponse> response = service.reprocessAllDltMessages();
        verify(applicationTaskExecutor).execute(worker.capture());
        worker.getValue().run();

        assertThat(response).isCompletedWithValueMatching(result ->
                result.totalMessages() == 2
                        && result.successfullyReprocessed() == 0
                        && result.failed() == 2
                        && result.details().size() == 2);
        verify(consumer, times(1)).poll(any(Duration.class));
        verify(distributedLock).close();
    }

    private ConsumerRecord<String, String> invalidRecord(TopicPartition topicPartition, long offset) {
        return new ConsumerRecord<>(
                topicPartition.topic(),
                topicPartition.partition(),
                offset,
                "key-" + offset,
                "invalid-json"
        );
    }
}
