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
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
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

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicProperties topicProperties;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${app.kafka.topics.low-stock.reprocess-batch-size:100}")
    private int reprocessBatchSize;

    @Value("${app.kafka.topics.low-stock.reprocess-send-timeout-sec:10}")
    private int sendTimeoutSec;

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_EMPTY_POLLS = 3;

    @Async
    @Override
    public CompletableFuture<DltReprocessResponse> reprocessAllDltMessages() {
        String dltTopic = topicProperties.getName() + ".DLT";
        String mainTopic = topicProperties.getName();

        log.info("Starting DLT reprocessing for topic: {} batchSize={}", dltTopic, reprocessBatchSize);

        List<DltReprocessDetail> details = new ArrayList<>();
        int totalMessages = 0;
        int successfullyReprocessed = 0;
        int failed = 0;
        int batchCount = 0;
        int skippedDuplicates = 0;

        // Дедупликация по ключу в рамках одного батча
        Set<String> processedKeys = new HashSet<>();

        // Отслеживаем максимальный offset по каждой партиции для удаления
        Map<TopicPartition, Long> maxProcessedOffsets = new HashMap<>();

        try (Consumer<String, String> consumer = createDltConsumer()) {

            List<TopicPartition> partitions = assignPartitions(consumer, dltTopic);
            int emptyPolls = 0;

            while (emptyPolls < MAX_EMPTY_POLLS && batchCount < reprocessBatchSize) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);

                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }

                emptyPolls = 0;

                for (ConsumerRecord<String, String> record : records) {
                    if (batchCount >= reprocessBatchSize) {
                        log.info("Batch limit reached: {}", reprocessBatchSize);
                        break;
                    }

                    totalMessages++;
                    String recordKey = record.key() != null ? record.key() : "";

                    // Проверка на дубли в рамках текущего батча
                    if (!processedKeys.add(recordKey + "@" + record.partition() + "@" + record.offset())) {
                        skippedDuplicates++;
                        log.warn("Skipping duplicate DLT message: key={}, partition={}, offset={}",
                                recordKey, record.partition(), record.offset());
                        continue;
                    }

                    TopicPartition tp = new TopicPartition(record.topic(), record.partition());

                    try {
                        // Синхронная отправка в main topic с таймаутом
                        reprocessSingleMessageSync(record, mainTopic);

                        // Коммитим offset
                        Map<TopicPartition, OffsetAndMetadata> offsetToCommit = new HashMap<>();
                        offsetToCommit.put(tp, new OffsetAndMetadata(record.offset() + 1));
                        consumer.commitSync(offsetToCommit);

                        // Отслеживаем максимальный обработанный offset для удаления
                        maxProcessedOffsets.merge(tp, record.offset() + 1, Math::max);

                        successfullyReprocessed++;
                        batchCount++;

                        details.add(new DltReprocessDetail(
                                record.key(),
                                parseTimestamp(record),
                                getExceptionMessage(record),
                                true,
                                null
                        ));
                    } catch (Exception e) {
                        failed++;
                        details.add(new DltReprocessDetail(
                                record.key(),
                                parseTimestamp(record),
                                getExceptionMessage(record),
                                false,
                                e.getMessage()
                        ));
                        log.error("Failed to reprocess DLT msg: partition={}, offset={}, error={}",
                                record.partition(), record.offset(), e.getMessage());
                    }
                }
            }

            // Удаляем обработанные записи из DLT
            deleteProcessedRecords(dltTopic, maxProcessedOffsets);

        } catch (Exception e) {
            log.error("DLT reprocessing error: {}", e.getMessage());
            return CompletableFuture.completedFuture(new DltReprocessResponse(
                    totalMessages, successfullyReprocessed, failed, details));
        }

        log.info("DLT reprocessing done: total={}, success={}, failed={}, skippedDuplicates={}, batch={}",
                totalMessages, successfullyReprocessed, failed, skippedDuplicates, batchCount);

        return CompletableFuture.completedFuture(new DltReprocessResponse(
                totalMessages, successfullyReprocessed, failed, details));
    }

    private void deleteProcessedRecords(String topic, Map<TopicPartition, Long> offsetsToDelete) {
        if (offsetsToDelete.isEmpty()) {
            return;
        }

        Map<TopicPartition, RecordsToDelete> recordsToDelete = new HashMap<>();
        for (Map.Entry<TopicPartition, Long> entry : offsetsToDelete.entrySet()) {
            // Проверяем, что offset > 0 (есть что удалять)
            if (entry.getValue() > 0) {
                recordsToDelete.put(entry.getKey(), RecordsToDelete.beforeOffset(entry.getValue()));
            }
        }

        if (recordsToDelete.isEmpty()) {
            log.debug("No records to delete (all offsets are 0)");
            return;
        }

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        try (AdminClient adminClient = AdminClient.create(props)) {
            // Сначала получаем текущие earliest offsets
            Map<TopicPartition, OffsetSpec> earliestSpecs = recordsToDelete.keySet().stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.earliest()));

            Map<TopicPartition, org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo> earliestOffsets;
            try {
                earliestOffsets = adminClient.listOffsets(earliestSpecs).all().get(sendTimeoutSec, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Failed to get earliest offsets: {}", e.getMessage());
                return;
            }

            Map<TopicPartition, RecordsToDelete> validDeletes = new HashMap<>();
            for (Map.Entry<TopicPartition, RecordsToDelete> entry : recordsToDelete.entrySet()) {
                long earliestOffset = earliestOffsets.get(entry.getKey()).offset();
                long deleteOffset = offsetsToDelete.get(entry.getKey());

                if (deleteOffset > earliestOffset) {
                    validDeletes.put(entry.getKey(), entry.getValue());
                } else {
                    log.debug("Skipping delete for {}: offset {} <= earliest {}",
                            entry.getKey(), deleteOffset, earliestOffset);
                }
            }

            if (!validDeletes.isEmpty()) {
                adminClient.deleteRecords(validDeletes).all().get(sendTimeoutSec, TimeUnit.SECONDS);
                log.info("Deleted records from DLT up to offsets: {}", offsetsToDelete);
            }
        } catch (Exception e) {
            log.warn("Failed to delete processed records from DLT: {}", e.getMessage());
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
            return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
        }
        var causeHeader = record.headers().lastHeader("kafka_dlt-exception-cause-fqcn");
        if (causeHeader != null) {
            return new String(causeHeader.value());
        }
        return "Unknown";
    }
}
