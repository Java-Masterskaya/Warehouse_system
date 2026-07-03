package com.warehouse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.dto.response.DltReprocessDetail;
import com.warehouse.dto.response.DltReprocessResponse;
import com.warehouse.kafka.config.KafkaTopicProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DltReprocessingService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicProperties topicProperties;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${app.kafka.topics.low-stock.reprocess-batch-size:100}")
    private int reprocessBatchSize;

    private static final String DLT_TOPIC = "low-stock-alerts.DLT";
    private static final String MAIN_TOPIC = "low-stock-alerts";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_EMPTY_POLLS = 3;

    /**
     * Вычитывает сообщения из DLT пакетами и отправляет их обратно в основной топик.
     * Обрабатывает не более reprocessBatchSize сообщений за один вызов.
     * @return статистика по перепrocessed сообщениям
     */
    public DltReprocessResponse reprocessAllDltMessages() {
        log.info("Starting DLT reprocessing for topic: {} with batch size: {}", DLT_TOPIC, reprocessBatchSize);

        List<DltReprocessDetail> details = new ArrayList<>();
        int totalMessages = 0;
        int successfullyReprocessed = 0;
        int failed = 0;
        int batchCount = 0;

        try (Consumer<String, String> consumer = createDltConsumer()) {
            List<TopicPartition> partitions = assignPartitions(consumer);

            consumer.seekToBeginning(partitions);

            int emptyPolls = 0;

            while (emptyPolls < MAX_EMPTY_POLLS && batchCount < reprocessBatchSize) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);

                if (records.isEmpty()) {
                    emptyPolls++;
                } else {
                    emptyPolls = 0;

                    for (ConsumerRecord<String, String> record : records) {
                        if (batchCount >= reprocessBatchSize) {
                            log.info("Reached batch size limit: {}, stopping", reprocessBatchSize);
                            break;
                        }

                        totalMessages++;
                        try {
                            reprocessSingleMessage(record);
                            successfullyReprocessed++;
                            batchCount++;
                            details.add(new DltReprocessDetail(
                                    record.key(),
                                    parseTimestamp(record),
                                    getExceptionMessage(record),
                                    true,
                                    null
                            ));
                            // Подтверждаем оффсет после успешной отправки
                            consumer.commitSync();
                        } catch (Exception e) {
                            failed++;
                            details.add(new DltReprocessDetail(
                                    record.key(),
                                    parseTimestamp(record),
                                    getExceptionMessage(record),
                                    false,
                                    e.getMessage()
                            ));
                            log.error("Failed to reprocess message from DLT: partition={}, offset={}",
                                    record.partition(), record.offset(), e);
                        }
                    }
                }
            }
        }

        log.info("DLT reprocessing completed: total={}, success={}, failed={}, batchProcessed={}",
                totalMessages, successfullyReprocessed, failed, batchCount);

        return new DltReprocessResponse(totalMessages, successfullyReprocessed, failed, details);
    }

    private Consumer<String, String> createDltConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, 
                org.apache.kafka.common.serialization.StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
                org.apache.kafka.common.serialization.StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reprocess-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        return new org.apache.kafka.clients.consumer.KafkaConsumer<>(props);
    }

    private List<TopicPartition> assignPartitions(Consumer<String, String> consumer) {
        List<TopicPartition> partitions = new ArrayList<>();
        int partitionCount = topicProperties.getPartitions();

        for (int i = 0; i < partitionCount; i++) {
            partitions.add(new TopicPartition(DLT_TOPIC, i));
        }

        consumer.assign(partitions);
        return partitions;
    }

    private void reprocessSingleMessage(ConsumerRecord<String, String> record) throws Exception {
        // Извлекаем JSON из value
        String value = record.value();
        
        // Парсим в LowStockAlertEvent
        LowStockAlertEvent event = objectMapper.readValue(value, LowStockAlertEvent.class);

        // Отправляем обратно в основной топик
        kafkaTemplate.send(new ProducerRecord<>(MAIN_TOPIC, record.key(), event)).get();
        
        log.info("Reprocessed message from DLT: key={}, partition={}, offset={}", 
                record.key(), record.partition(), record.offset());
    }

    private LocalDateTime parseTimestamp(ConsumerRecord<String, String> record) {
        // Используем timestamp из метаданных Kafka
        return Instant.ofEpochMilli(record.timestamp())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    private String getExceptionMessage(ConsumerRecord<String, String> record) {
        // Ищем заголовок с причиной ошибки
        var header = record.headers().lastHeader("kafka_dlt-exception-message");
        if (header != null) {
            return new String(header.value());
        }
        
        // Альтернативно - kafka_dlt-exception-cause-fqcn
        var causeHeader = record.headers().lastHeader("kafka_dlt-exception-cause-fqcn");
        if (causeHeader != null) {
            return new String(causeHeader.value());
        }
        
        return "Unknown";
    }
}
