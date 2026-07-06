package com.warehouse.kafka.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.dto.response.DltReprocessDetail;
import com.warehouse.dto.response.DltReprocessResponse;
import com.warehouse.kafka.config.KafkaTopicProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DltReprocessingServiceImpl implements DltReprocessingService {

    private static final int MAX_EXCEPTION_MESSAGE_LENGTH = 200;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_EMPTY_POLLS = 3;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicProperties topicProperties;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${app.kafka.topics.low-stock.reprocess-batch-size:100}")
    private int reprocessBatchSize;

    @Value("${app.kafka.topics.low-stock.reprocess-send-timeout-sec:10}")
    private int sendTimeoutSec;

    @Async
    @Override
    public CompletableFuture<DltReprocessResponse> reprocessAllDltMessages() {
        String dltTopic = topicProperties.getName() + ".DLT";
        String mainTopic = topicProperties.getName();

        log.info("Starting DLT reprocessing for topic: {} batchSize={}", dltTopic, reprocessBatchSize);

        try (Consumer<String, String> consumer = createDltConsumer()) {
            assignPartitions(consumer, dltTopic);
            ReprocessResult result = processMessages(consumer, mainTopic);
            deleteProcessedRecords(dltTopic, result.maxProcessedOffsets());
            logResult(result);
            return CompletableFuture.completedFuture(result.toResponse());
        } catch (Exception e) {
            log.error("DLT reprocessing error: {}", e.getMessage());
            return CompletableFuture.completedFuture(new DltReprocessResponse(0, 0, 0, List.of()));
        }
    }

    private ReprocessResult processMessages(Consumer<String, String> consumer, String mainTopic) {
        List<DltReprocessDetail> details = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>();
        Map<TopicPartition, Long> maxProcessedOffsets = new HashMap<>();
        int totalMessages = 0;
        int successfullyReprocessed = 0;
        int failed = 0;
        int batchCount = 0;
        int skippedDuplicates = 0;
        int emptyPolls = 0;

        while (emptyPolls < MAX_EMPTY_POLLS && batchCount < reprocessBatchSize) {
            ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);

            if (records.isEmpty()) {
                emptyPolls++;
                continue;
            }

            emptyPolls = 0;
            BatchProcessResult batchResult = processRecordBatch(
                    records, mainTopic, consumer, processedKeys, batchCount);

            totalMessages += batchResult.totalMessages();
            successfullyReprocessed += batchResult.successCount();
            failed += batchResult.failedCount();
            skippedDuplicates += batchResult.skippedDuplicates();
            batchCount += batchResult.processedCount();
            details.addAll(batchResult.details());
            mergeOffsets(maxProcessedOffsets, batchResult.maxProcessedOffsets());
        }

        return new ReprocessResult(totalMessages, successfullyReprocessed, failed,
                skippedDuplicates, batchCount, details, maxProcessedOffsets);
    }

    private BatchProcessResult processRecordBatch(ConsumerRecords<String, String> records,
                                                  String mainTopic,
                                                  Consumer<String, String> consumer,
                                                  Set<String> processedKeys,
                                                  int currentBatchCount) {
        List<DltReprocessDetail> details = new ArrayList<>();
        Map<TopicPartition, Long> maxProcessedOffsets = new HashMap<>();
        int totalMessages = 0;
        int successCount = 0;
        int failedCount = 0;
        int skippedDuplicates = 0;
        int processedCount = 0;

        for (ConsumerRecord<String, String> record : records) {
            if (currentBatchCount + processedCount >= reprocessBatchSize) {
                log.info("Batch limit reached: {}", reprocessBatchSize);
                break;
            }

            totalMessages++;
            String recordKey = getRecordKey(record);
            String dedupKey = recordKey + "@" + record.partition() + "@" + record.offset();

            if (!processedKeys.add(dedupKey)) {
                skippedDuplicates++;
                log.warn("Skipping duplicate DLT message: key={}, partition={}, offset={}",
                        recordKey, record.partition(), record.offset());
                details.add(createDetail(record, false, "Duplicate in batch"));
                continue;
            }

            SingleProcessResult singleResult = processSingleRecord(record, mainTopic, consumer);
            details.add(singleResult.detail());

            if (singleResult.success()) {
                successCount++;
                processedCount++;
                maxProcessedOffsets.merge(singleResult.topicPartition(), singleResult.offset(), Math::max);
            } else {
                failedCount++;
            }
        }

        return new BatchProcessResult(totalMessages, successCount, failedCount,
                skippedDuplicates, processedCount, details, maxProcessedOffsets);
    }

    private String getRecordKey(ConsumerRecord<String, String> record) {
        if (record.key() != null) {
            return record.key();
        }
        return "";
    }

    private SingleProcessResult processSingleRecord(ConsumerRecord<String, String> record,
                                                    String mainTopic,
                                                    Consumer<String, String> consumer) {
        TopicPartition tp = new TopicPartition(record.topic(), record.partition());

        try {
            reprocessSingleMessageSync(record, mainTopic);
            return SingleProcessResult.success(tp, record.offset() + 1,
                    createDetail(record, true, null));
        } catch (Exception e) {
            log.error("Failed to reprocess DLT msg: partition={}, offset={}, error={}",
                    record.partition(), record.offset(), e.getMessage());
            return SingleProcessResult.failure(createDetail(record, false, e.getMessage()));
        }
    }

    private DltReprocessDetail createDetail(ConsumerRecord<String, String> record,
                                            boolean success, String error) {
        return new DltReprocessDetail(
                record.key(),
                parseTimestamp(record),
                getExceptionMessage(record),
                success,
                error
        );
    }

    private void mergeOffsets(Map<TopicPartition, Long> target, Map<TopicPartition, Long> source) {
        source.forEach((tp, offset) -> target.merge(tp, offset, Math::max));
    }

    private void logResult(ReprocessResult result) {
        log.info("DLT reprocessing done: total={}, success={}, failed={}, skippedDuplicates={}, batch={}",
                result.totalMessages(), result.successfullyReprocessed(), result.failed(),
                result.skippedDuplicates(), result.batchCount());
    }

    private void deleteProcessedRecords(String topic, Map<TopicPartition, Long> offsetsToDelete) {
        if (offsetsToDelete.isEmpty()) {
            return;
        }

        Map<TopicPartition, RecordsToDelete> recordsToDelete = buildRecordsToDelete(offsetsToDelete);
        if (recordsToDelete.isEmpty()) {
            log.debug("No records to delete (all offsets are 0)");
            return;
        }

        try (AdminClient adminClient = createAdminClient()) {
            Map<TopicPartition, Long> validDeletes = filterValidDeletes(adminClient, recordsToDelete, offsetsToDelete);
            if (!validDeletes.isEmpty()) {
                executeDeleteRecords(adminClient, validDeletes, offsetsToDelete);
            }
        } catch (Exception e) {
            log.warn("Failed to delete processed records from DLT: {}", e.getMessage());
        }
    }

    private Map<TopicPartition, RecordsToDelete> buildRecordsToDelete(Map<TopicPartition, Long> offsetsToDelete) {
        Map<TopicPartition, RecordsToDelete> recordsToDelete = new HashMap<>();
        for (Map.Entry<TopicPartition, Long> entry : offsetsToDelete.entrySet()) {
            if (entry.getValue() > 0) {
                recordsToDelete.put(entry.getKey(), RecordsToDelete.beforeOffset(entry.getValue()));
            }
        }
        return recordsToDelete;
    }

    private Map<TopicPartition, Long> filterValidDeletes(AdminClient adminClient,
                                                         Map<TopicPartition, RecordsToDelete> recordsToDelete,
                                                         Map<TopicPartition, Long> offsetsToDelete) {
        Map<TopicPartition, OffsetSpec> earliestSpecs = recordsToDelete.keySet().stream()
                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.earliest()));

        Map<TopicPartition, org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo> earliestOffsets;
        try {
            earliestOffsets = adminClient.listOffsets(earliestSpecs).all().get(sendTimeoutSec, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to get earliest offsets: {}", e.getMessage());
            return Map.of();
        }

        Map<TopicPartition, Long> validDeletes = new HashMap<>();
        for (Map.Entry<TopicPartition, RecordsToDelete> entry : recordsToDelete.entrySet()) {
            long earliestOffset = earliestOffsets.get(entry.getKey()).offset();
            long deleteOffset = offsetsToDelete.get(entry.getKey());

            if (deleteOffset > earliestOffset) {
                validDeletes.put(entry.getKey(), deleteOffset);
            } else {
                log.debug("Skipping delete for {}: offset {} <= earliest {}",
                        entry.getKey(), deleteOffset, earliestOffset);
            }
        }
        return validDeletes;
    }

    private void executeDeleteRecords(AdminClient adminClient,
                                      Map<TopicPartition, Long> validDeletes,
                                      Map<TopicPartition, Long> offsetsToDelete) {
        Map<TopicPartition, RecordsToDelete> deleteMap = new HashMap<>();
        validDeletes.forEach((tp, offset) -> deleteMap.put(tp, RecordsToDelete.beforeOffset(offset)));

        try {
            adminClient.deleteRecords(deleteMap).all().get(sendTimeoutSec, TimeUnit.SECONDS);
            log.info("Deleted records from DLT up to offsets: {}", offsetsToDelete);
        } catch (Exception e) {
            log.warn("Failed to delete records: {}", e.getMessage());
        }
    }

    private Consumer<String, String> createDltConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reprocess-service");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_uncommitted");

        return new KafkaConsumer<>(props);
    }

    private List<TopicPartition> assignPartitions(Consumer<String, String> consumer, String dltTopic) {
        List<TopicPartition> partitions = new ArrayList<>();
        var topicPartitions = consumer.partitionsFor(dltTopic);
        for (var tp : topicPartitions) {
            partitions.add(new TopicPartition(tp.topic(), tp.partition()));
        }
        consumer.assign(partitions);
        return partitions;
    }

    private void reprocessSingleMessageSync(ConsumerRecord<String, String> record, String mainTopic) throws Exception {
        String value = record.value();
        LowStockAlertEvent event = objectMapper.readValue(value, LowStockAlertEvent.class);

        try {
            SendResult<String, Object> result = kafkaTemplate.send(
                    new ProducerRecord<>(mainTopic, record.key(), event)
            ).get(sendTimeoutSec, TimeUnit.SECONDS);

            log.debug("Reprocessed to main topic: key={}, partition={}, offset={}",
                    record.key(), result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        } catch (TimeoutException e) {
            log.error("Timeout sending message to main topic after {}s: key={}", sendTimeoutSec, record.key());
            throw new RuntimeException("Kafka send timeout after " + sendTimeoutSec + "s", e);
        }
    }

    private LocalDateTime parseTimestamp(ConsumerRecord<String, String> record) {
        return Instant.ofEpochMilli(record.timestamp())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    private String getExceptionMessage(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("kafka_dlt-exception-message");
        if (header != null) {
            String msg = new String(header.value());
            if (msg.length() > MAX_EXCEPTION_MESSAGE_LENGTH) {
                return msg.substring(0, MAX_EXCEPTION_MESSAGE_LENGTH) + "...";
            }
            return msg;
        }
        var causeHeader = record.headers().lastHeader("kafka_dlt-exception-cause-fqcn");
        if (causeHeader != null) {
            return new String(causeHeader.value());
        }
        return "Unknown";
    }

    private AdminClient createAdminClient() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return AdminClient.create(props);
    }

    // --- Result records for method extraction ---

    private record ReprocessResult(int totalMessages, int successfullyReprocessed, int failed,
                                   int skippedDuplicates, int batchCount, List<DltReprocessDetail> details,
                                   Map<TopicPartition, Long> maxProcessedOffsets) {
        DltReprocessResponse toResponse() {
            return new DltReprocessResponse(totalMessages, successfullyReprocessed, failed, details);
        }
    }

    private record BatchProcessResult(int totalMessages, int successCount, int failedCount,
                                      int skippedDuplicates, int processedCount,
                                      List<DltReprocessDetail> details,
                                      Map<TopicPartition, Long> maxProcessedOffsets) {
    }

    private record SingleProcessResult(boolean success, TopicPartition topicPartition,
                                       long offset, DltReprocessDetail detail) {
        static SingleProcessResult success(TopicPartition tp, long offset, DltReprocessDetail detail) {
            return new SingleProcessResult(true, tp, offset, detail);
        }

        static SingleProcessResult failure(DltReprocessDetail detail) {
            return new SingleProcessResult(false, null, -1, detail);
        }
    }
}