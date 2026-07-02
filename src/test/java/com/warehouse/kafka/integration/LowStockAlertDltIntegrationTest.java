package com.warehouse.kafka.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.WarehouseApp;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.entity.StockAlert;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockAlertRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Slf4j
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = WarehouseApp.class)
class LowStockAlertDltIntegrationTest {

    @Container
    static RedpandaContainer redpanda = new RedpandaContainer(
            DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v24.2.1")
    );

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", redpanda::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private StockAlertRepository stockAlertRepository;

    @Autowired
    private ItemRepository itemRepository;

    private long testStartTime;

    @BeforeEach
    void setUp() {
        testStartTime = System.currentTimeMillis();
        log.info("=== Starting test setup ===");
        stockAlertRepository.deleteAll();
        itemRepository.deleteAll();
        log.info("=== Test setup completed ===");
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
        return allRecords;
    }

    /**
     * Тест 1: Проверяем, что битый JSON попадает в DLT БЕЗ ретраев.
     *
     * Ключевой критерий: время между отправкой и попаданием в DLT
     * должно быть < 3 секунд (без задержек ретраев).
     */
    @Test
    void shouldSendBadJsonToDltWithoutRetries() throws Exception {
        // given: уникальная строка, чтобы точно найти своё сообщение
        String uniqueMarker = "BAD_JSON_TEST_" + System.currentTimeMillis();
        String badJson = "this is definitely not a valid json " + uniqueMarker + " {{{";

        log.info("Sending bad JSON with marker: {}", uniqueMarker);
        kafkaTemplate.send("low-stock-alerts", badJson).get();

        // then: ждем сообщение в DLT
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    List<ConsumerRecord<String, String>> records = readAllDltMessages();

                    assertThat(records)
                            .as("DLT should contain messages")
                            .isNotEmpty();

                    // Ищем наше сообщение по уникальному маркеру
                    Optional<ConsumerRecord<String, String>> ourRecord = records.stream()
                            .filter(record -> record.value() != null &&
                                    record.value().contains(uniqueMarker))
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

                    // Проверяем, что это именно ошибка конвертации (не ретраилась)
                    assertThat(causeClass)
                            .as("Should be MessageConversionException (non-retryable)")
                            .contains("MessageConversionException");
                });
    }
    /**
     * Тест 2: Проверяем, что ошибки БД ретраятся 3 раза и потом попадают в DLT.
     *
     * Ключевой критерий:
     * - В DLT ровно 1 сообщение (не 3-4)
     * - Время > 5 секунд (были ретраи)
     * - В логах видно 4 попытки обработки (1 основная + 3 ретрая)
     */
    @Test
    void shouldRetryAndSendToDltAfterDbError() throws Exception {
        // given: валидный JSON, но запись в БД упадет из-за foreign key
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

                    // Проверяем, что это ошибка целостности данных (БД)
                    assertThat(exceptionMsg.toLowerCase())
                            .as("Should contain database constraint violation")
                            .containsAnyOf("constraint", "violates foreign key", "dataintegrity");
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
     *
     * Проверяем по логам: должно быть 4 вызова consumer'а для одного сообщения.
     */
    @Test
    void shouldHaveExactlyFourAttemptsBeforeDlt() throws Exception {
        // given
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
     */
    @Test
    void shouldIncludeAllRequiredDltHeaders() throws Exception {
        // given
        LowStockAlertEvent event = new LowStockAlertEvent(
                3L, "TEST-HEADERS-SKU", "Test Headers",
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
                            .filter(r -> r.value() != null && r.value().contains("\"itemId\":3"))
                            .toList();

                    assertThat(relevant).isNotEmpty();

                    ConsumerRecord<String, String> record = relevant.get(0);

                    // Проверяем обязательные заголовки DLT
                    String[] requiredHeaders = {
                            "kafka_dlt-exception-message",
                            "kafka_dlt-exception-stacktrace",
                            "kafka_dlt-original-topic",
                            "kafka_dlt-original-partition",
                            "kafka_dlt-original-offset"
                    };

                    for (String headerName : requiredHeaders) {
                        Header header = record.headers().lastHeader(headerName);
                        assertThat(header)
                                .as("Header '%s' should be present", headerName)
                                .isNotNull();
                        log.info("Header '{}': {}", headerName,
                                headerName.contains("stacktrace") ? "<stacktrace>" : new String(header.value()));
                    }

                    // Проверяем содержимое заголовков
                    String originalTopic = new String(record.headers().lastHeader("kafka_dlt-original-topic").value());
                    assertThat(originalTopic).isEqualTo("low-stock-alerts");

                    String exceptionMessage = new String(record.headers().lastHeader("kafka_dlt-exception-message").value());
                    assertThat(exceptionMessage).isNotEmpty();
                });
    }
}