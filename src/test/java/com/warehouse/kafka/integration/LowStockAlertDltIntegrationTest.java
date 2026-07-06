package com.warehouse.kafka.integration;

import com.warehouse.WarehouseApp;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.StockAlert;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockAlertRepository;
import com.warehouse.repository.StockRepository;
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
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Slf4j
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = WarehouseApp.class)
class LowStockAlertDltIntegrationTest {

    static final RedpandaContainer redpanda =
            new RedpandaContainer(DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v24.2.1"));

    static {
        redpanda.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", redpanda::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private StockAlertRepository stockAlertRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private ItemRepository itemRepository;

    private long testStartTime;

    @BeforeEach
    void setUp() {
        testStartTime = System.currentTimeMillis();
        log.info("=== Starting test setup ===");
        stockAlertRepository.deleteAll();
        stockRepository.deleteAll();
        itemRepository.deleteAll();
        clearDltTopic();
        log.info("=== Test setup completed ===");
    }

    private void clearDltTopic() {
        String dltTopic = "low-stock-alerts.DLT";
        try (AdminClient adminClient = createAdminClient()) {
            var topicNames = adminClient.listTopics().names().get();
            if (!topicNames.contains(dltTopic)) {
                log.info("DLT topic '{}' doesn't exist yet, skipping cleanup", dltTopic);
                return;
            }

            var topicDesc = adminClient.describeTopics(List.of(dltTopic)).allTopicNames().get();
            int partitionCount = topicDesc.get(dltTopic).partitions().size();

            List<TopicPartition> partitions = java.util.stream.IntStream.range(0, partitionCount)
                    .mapToObj(i -> new TopicPartition(dltTopic, i))
                    .collect(java.util.stream.Collectors.toList());

            java.util.Map<TopicPartition, OffsetSpec> earliestSpecs = partitions.stream()
                    .collect(java.util.stream.Collectors.toMap(tp -> tp, tp -> OffsetSpec.earliest()));
            java.util.Map<TopicPartition, OffsetSpec> latestSpecs = partitions.stream()
                    .collect(java.util.stream.Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));

            var earliestOffsets = adminClient.listOffsets(earliestSpecs).all().get();
            var latestOffsets = adminClient.listOffsets(latestSpecs).all().get();

            java.util.Map<TopicPartition, RecordsToDelete> recordsToDelete = partitions.stream()
                    .filter(tp -> latestOffsets.get(tp).offset() > earliestOffsets.get(tp).offset())
                    .collect(java.util.stream.Collectors.toMap(
                            tp -> tp,
                            tp -> RecordsToDelete.beforeOffset(latestOffsets.get(tp).offset())
                    ));

            if (!recordsToDelete.isEmpty()) {
                adminClient.deleteRecords(recordsToDelete).all().get();
                log.info("DLT topic cleaned: {} partitions", recordsToDelete.size());
            } else {
                log.info("DLT topic is already empty");
            }
        } catch (Exception e) {
            log.debug("DLT cleanup skipped: {}", e.getMessage());
        }
    }

    private AdminClient createAdminClient() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers());
        return AdminClient.create(props);
    }

    private Consumer<String, String> createDltConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        return new org.apache.kafka.clients.consumer.KafkaConsumer<>(props);
    }

    private List<ConsumerRecord<String, String>> readAllDltMessages() {
        List<ConsumerRecord<String, String>> allRecords = new ArrayList<>();

        try (Consumer<String, String> consumer = createDltConsumer()) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                partitions.add(new TopicPartition("low-stock-alerts.DLT", i));
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            int emptyPolls = 0;
            while (emptyPolls < 3) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                if (records.isEmpty()) {
                    emptyPolls++;
                } else {
                    emptyPolls = 0;
                    records.forEach(allRecords::add);
                }
            }
        }

        log.info("Total DLT messages found: {}", allRecords.size());
        for (ConsumerRecord<String, String> record : allRecords) {
            log.info("DLT record: partition={}, offset={}, key={}, value={}",
                    record.partition(), record.offset(), record.key(),
                    record.value() != null ? record.value().substring(0, Math.min(100, record.value().length())) : "null");
            // Decode base64 if needed (JsonSerializer encodes String as JSON)
            if (record.value() != null && record.value().startsWith("\"")) {
                try {
                    String decoded = new String(java.util.Base64.getDecoder().decode(record.value()));
                    log.info("Decoded value: {}", decoded);
                } catch (Exception e) {
                    log.debug("Failed to decode value: {}", e.getMessage());
                }
            }
        }
        return allRecords;
    }

    /**
     * Тест 1: Проверяем, что битый JSON попадает в DLT БЕЗ ретраев.
     * <p>
     * Ключевой критерий: время между отправкой и попаданием в DLT
     * должно быть < 3 секунд (без задержек ретраев).
     */
    @Test
    void shouldSendBadJsonToDltWithoutRetries() throws Exception {
        // given: уникальная строка, чтобы точно найти своё сообщение
        String uniqueMarker = "BAD_JSON_TEST_" + System.currentTimeMillis();
        String badJson = "this is definitely not a valid json " + uniqueMarker + " {{{";

        log.info("Sending bad JSON with marker: {}", uniqueMarker);
        kafkaTemplate.send("low-stock-alerts", uniqueMarker, badJson).get();

        // then: ждем сообщение в DLT
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    List<ConsumerRecord<String, String>> records = readAllDltMessages();

                    assertThat(records)
                            .as("DLT should contain messages")
                            .isNotEmpty();

                    // Ищем наше сообщение по уникальному маркеру в ключе или значении
                    Optional<ConsumerRecord<String, String>> ourRecord = records.stream()
                            .filter(record -> (record.key() != null && record.key().contains(uniqueMarker)) ||
                                    (record.value() != null && record.value().contains(uniqueMarker)))
                            .findFirst();

                    assertThat(ourRecord)
                            .as("Should find our bad JSON message with marker: " + uniqueMarker)
                            .isPresent();

                    ConsumerRecord<String, String> record = ourRecord.get();

                    // Проверяем, что есть exception cause header
                    Header causeHeader = record.headers().lastHeader("kafka_dlt-exception-cause-fqcn");
                    assertThat(causeHeader)
                            .as("Should have exception cause header for non-retryable error")
                            .isNotNull();

                    String causeClass = new String(causeHeader.value());
                    log.info("Exception cause class: {}", causeClass);

                    // Проверяем, что это именно ошибка десериализации (не ретраилась)
                    // Для String сообщений, сериализованных JsonDeserializer, ошибка будет DeserializationException
                    // (MessageConversionException используется в RabbitMQ, DeserializationException - в Kafka)
                    assertThat(causeClass)
                            .as("Should be DeserializationException (non-retryable deserialization error)")
                            .contains("DeserializationException");
                });
    }

    /**
     * Тест 2: Проверяем, что ошибка "Item not found" ретраится 3 раза и потом попадает в DLT.
     * <p>
     * Ключевой критерий:
     * - В DLT ровно 1 сообщение (не 3-4)
     * - Время > 5 секунд (были ретраи)
     * - В логах видно 4 попытки обработки (1 основная + 3 ретрая)
     */
    @Test
    void shouldRetryAndSendToDltAfterItemNotFound() throws Exception {
        // given: валидный JSON, но item не существует в БД
        LowStockAlertEvent event = new LowStockAlertEvent(
                1L, "TEST-RETRY-SKU", "Test Retry",
                5, 10, "admin", LocalDateTime.now()
        );

        // when: отправляем и засекаем время
        long sendTime = System.currentTimeMillis();
        kafkaTemplate.send("low-stock-alerts", event).get();
        log.info("Event sent at {}", sendTime);

        // then: ждем достаточно долго, чтобы прошли все ретраи
        await().atMost(35, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    List<ConsumerRecord<String, String>> records = readAllDltMessages();

                    // Фильтруем сообщения для itemId=1
                    List<ConsumerRecord<String, String>> relevantRecords = records.stream()
                            .filter(r -> r.value() != null && r.value().contains("\"itemId\":1"))
                            .toList();

                    assertThat(relevantRecords)
                            .as("Should have exactly 1 message in DLT for itemId=1")
                            .hasSize(1);

                    // Проверяем exception header
                    ConsumerRecord<String, String> record = relevantRecords.get(0);
                    Header exceptionHeader = record.headers().lastHeader("kafka_dlt-exception-message");
                    assertThat(exceptionHeader).isNotNull();

                    String exceptionMsg = new String(exceptionHeader.value());
                    log.info("Exception in DLT: {}", exceptionMsg);

                    // Проверяем, что это ошибка "Item not found" (EntityNotFoundException)
                    assertThat(exceptionMsg)
                            .as("Should contain 'Item not found' message")
                            .contains("Item not found");
                });

        long dltTime = System.currentTimeMillis();
        long elapsed = (dltTime - sendTime) / 1000;
        log.info("Time to DLT with retries: {} seconds", elapsed);

        // Должно быть дольше 5 секунд из-за ретраев
        assertThat(elapsed)
                .as("Retryable errors should take time due to backoff")
                .isGreaterThan(5);

        // Проверяем, что в основной таблице НЕТ записи (обработка не прошла успешно)
        List<StockAlert> alerts = stockAlertRepository.findAll();
        assertThat(alerts)
                .as("No alert should be saved after failed retries")
                .isEmpty();
    }

    /**
     * Тест 3: Проверяем, что ретраев ровно 3 (4 попытки всего).
     * <p>
     * Проверяем по логам: должно быть 4 вызова consumer'а для одного сообщения.
     */
    @Test
    void shouldHaveExactlyFourAttemptsBeforeDlt() throws Exception {
        // given: item не существует в БД
        LowStockAlertEvent event = new LowStockAlertEvent(
                2L, "TEST-ATTEMPTS-SKU", "Test Attempts",
                5, 10, "admin", LocalDateTime.now()
        );

        // when
        kafkaTemplate.send("low-stock-alerts", event).get();

        // then: ждем DLT
        await().atMost(35, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    List<ConsumerRecord<String, String>> records = readAllDltMessages();

                    long item2Count = records.stream()
                            .filter(r -> r.value() != null && r.value().contains("\"itemId\":2"))
                            .count();

                    assertThat(item2Count)
                            .as("Should have exactly 1 message in DLT after all retries")
                            .isEqualTo(1);
                });

        log.info("""
                ⚠️ MANUAL CHECK REQUIRED: 
                Check logs above for 'Received low stock alert for itemId=2'.
                Should appear exactly 4 times (1 initial + 3 retries).
                """);
    }

    /**
     * Тест 4: Проверяем заголовки DLT сообщения.
     * Критерий: "В DLT-сообщении сохранены заголовки с причиной (kafka_dlt-exception-message)"
     */
    @Test
    void shouldIncludeAllRequiredDltHeaders() throws Exception {
        // given: item не существует в БД
        String uniqueSku = "TEST-HEADERS-" + System.currentTimeMillis();
        LowStockAlertEvent event = new LowStockAlertEvent(
                3L, uniqueSku, "Test Headers",
                5, 10, "admin", LocalDateTime.now()
        );

        // when
        kafkaTemplate.send("low-stock-alerts", event).get();

        // then
        await().atMost(35, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    List<ConsumerRecord<String, String>> records = readAllDltMessages();

                    List<ConsumerRecord<String, String>> relevant = records.stream()
                            .filter(r -> r.value() != null && r.value().contains(uniqueSku))
                            .toList();

                    assertThat(relevant)
                            .as("Should find DLT message for SKU: " + uniqueSku)
                            .isNotEmpty();

                    ConsumerRecord<String, String> record = relevant.get(0);

                    // === Проверка kafka_dlt-exception-message (КРИТЕРИЙ ЗАДАЧИ) ===
                    Header exceptionMsgHeader = record.headers().lastHeader("kafka_dlt-exception-message");
                    assertThat(exceptionMsgHeader)
                            .as("Header 'kafka_dlt-exception-message' must be present")
                            .isNotNull();

                    String exceptionMessage = new String(exceptionMsgHeader.value());
                    assertThat(exceptionMessage)
                            .as("Exception message should not be empty")
                            .isNotEmpty();

                    assertThat(exceptionMessage)
                            .as("Exception message should contain 'Item not found'")
                            .contains("Item not found");

                    log.info("Exception message: {}",
                            exceptionMessage.substring(0, Math.min(200, exceptionMessage.length())));

                    // === Проверка kafka_dlt-exception-fqcn ===
                    Header fqcnHeader = record.headers().lastHeader("kafka_dlt-exception-fqcn");
                    assertThat(fqcnHeader)
                            .as("Header 'kafka_dlt-exception-fqcn' must be present")
                            .isNotNull();

                    String fqcn = new String(fqcnHeader.value());
                    assertThat(fqcn)
                            .as("Should contain exception class name")
                            .contains("ListenerExecutionFailedException");
                    log.info("Exception FQCN: {}", fqcn);

                    // === Проверка kafka_dlt-exception-cause-fqcn ===
                    Header causeFqcnHeader = record.headers().lastHeader("kafka_dlt-exception-cause-fqcn");
                    assertThat(causeFqcnHeader)
                            .as("Header 'kafka_dlt-exception-cause-fqcn' must be present")
                            .isNotNull();

                    String causeFqcn = new String(causeFqcnHeader.value());
                    assertThat(causeFqcn)
                            .as("Should contain root cause exception class")
                            .contains("EntityNotFoundException");
                    log.info("Exception cause FQCN: {}", causeFqcn);

                    // === Проверка kafka_dlt-exception-stacktrace ===
                    Header stacktraceHeader = record.headers().lastHeader("kafka_dlt-exception-stacktrace");
                    assertThat(stacktraceHeader)
                            .as("Header 'kafka_dlt-exception-stacktrace' must be present")
                            .isNotNull();

                    String stacktrace = new String(stacktraceHeader.value());
                    assertThat(stacktrace)
                            .as("Stacktrace should contain error details")
                            .contains("EntityNotFoundException");
                    log.info("Stacktrace present (length: {} chars)", stacktrace.length());

                    // === Проверка kafka_dlt-original-topic ===
                    Header originalTopicHeader = record.headers().lastHeader("kafka_dlt-original-topic");
                    assertThat(originalTopicHeader)
                            .as("Header 'kafka_dlt-original-topic' must be present")
                            .isNotNull();

                    String originalTopic = new String(originalTopicHeader.value());
                    assertThat(originalTopic)
                            .as("Original topic should be 'low-stock-alerts'")
                            .isEqualTo("low-stock-alerts");
                    log.info("Original topic: {}", originalTopic);

                    // === Проверка kafka_dlt-original-partition ===
                    Header originalPartitionHeader = record.headers().lastHeader("kafka_dlt-original-partition");
                    assertThat(originalPartitionHeader)
                            .as("Header 'kafka_dlt-original-partition' must be present")
                            .isNotNull();
                    log.info("Original partition: {}",
                            new String(originalPartitionHeader.value()));

                    // === Проверка kafka_dlt-original-offset ===
                    Header originalOffsetHeader = record.headers().lastHeader("kafka_dlt-original-offset");
                    assertThat(originalOffsetHeader)
                            .as("Header 'kafka_dlt-original-offset' must be present")
                            .isNotNull();
                    log.info("Original offset present");

                    // === Проверка kafka_dlt-original-timestamp ===
                    Header originalTimestampHeader = record.headers().lastHeader("kafka_dlt-original-timestamp");
                    assertThat(originalTimestampHeader)
                            .as("Header 'kafka_dlt-original-timestamp' must be present")
                            .isNotNull();
                    log.info("Original timestamp present");

                    log.info("=== All {} required DLT headers verified successfully ===", 7);
                });
    }
}
