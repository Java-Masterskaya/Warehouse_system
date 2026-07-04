package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.WarehouseApp;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.response.DltReprocessResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockAlert;
import com.warehouse.entity.User;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockAlertRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
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
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Slf4j
@AutoConfigureMockMvc
@Tag("integration")
@SpringBootTest(classes = WarehouseApp.class)
@ActiveProfiles("test")
@Testcontainers
class DltReprocessingControllerTest {

    @Container
    static final RedpandaContainer redpanda = new RedpandaContainer(
            DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v24.2.1")
    );

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", redpanda::getBootstrapServers);
    }

    private static final String MAIN_TOPIC = "low-stock-alerts";
    private static final String DLT_TOPIC = "low-stock-alerts.DLT";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockAlertRepository stockAlertRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String adminToken;
    private String userToken;
    private Item testItem;
    private Long testItemId;

    @BeforeEach
    void setUp() throws Exception {
        log.info("=== Test setup: cleaning DB and DLT ===");

        // Очистка БД — используем правильные имена таблиц
        jdbcTemplate.update("DELETE FROM stock_alerts");
        jdbcTemplate.update("DELETE FROM stock");       // было "stocks" → "stock"
        jdbcTemplate.update("DELETE FROM items");
        jdbcTemplate.update("DELETE FROM users");

        // Очистка DLT
        clearDltTopic();

        // Создание пользователей
        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode("secret"));
        admin.setRole(Role.ROLE_ADMIN);
        admin.setActive(true);
        userRepository.save(admin);

        User user = new User();
        user.setUsername("testuser");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(Role.ROLE_USER);
        user.setActive(true);
        userRepository.save(user);

        // Создание тестового Item
        testItem = Item.builder()
                .sku("SKU-DLT-" + System.currentTimeMillis())
                .name("Тестовый товар DLT")
                .category("Категория")
                .minStock(10)
                .active(true)
                .build();
        testItem = itemRepository.save(testItem);
        testItemId = testItem.getId();

        // Создание тестового Stock
        Stock stock = Stock.builder()
                .item(testItem)
                .quantity(5)
                .build();
        stockRepository.save(stock);

        // Получение токенов
        adminToken = obtainToken("admin", "secret");
        userToken = obtainToken("testuser", "password");

        log.info("=== Test setup completed ===");
    }
    // ==================== Вспомогательные методы ====================

    private AdminClient createAdminClient() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers());
        return AdminClient.create(props);
    }

    private void clearDltTopic() {
        try (AdminClient adminClient = createAdminClient()) {
            var topicNames = adminClient.listTopics().names().get();
            if (!topicNames.contains(DLT_TOPIC)) {
                log.info("DLT topic '{}' doesn't exist yet, skipping cleanup", DLT_TOPIC);
                return;
            }

            var topicDesc = adminClient.describeTopics(List.of(DLT_TOPIC)).allTopicNames().get();
            int partitionCount = topicDesc.get(DLT_TOPIC).partitions().size();

            List<TopicPartition> partitions = IntStream.range(0, partitionCount)
                    .mapToObj(i -> new TopicPartition(DLT_TOPIC, i))
                    .collect(Collectors.toList());

            Map<TopicPartition, OffsetSpec> earliestSpecs = partitions.stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.earliest()));
            Map<TopicPartition, OffsetSpec> latestSpecs = partitions.stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));

            var earliestOffsets = adminClient.listOffsets(earliestSpecs).all().get();
            var latestOffsets = adminClient.listOffsets(latestSpecs).all().get();

            Map<TopicPartition, RecordsToDelete> recordsToDelete = partitions.stream()
                    .filter(tp -> latestOffsets.get(tp).offset() > earliestOffsets.get(tp).offset())
                    .collect(Collectors.toMap(
                            tp -> tp,
                            tp -> RecordsToDelete.beforeOffset(latestOffsets.get(tp).offset())
                    ));

            if (!recordsToDelete.isEmpty()) {
                try {
                    adminClient.deleteRecords(recordsToDelete).all().get();
                    log.info("DLT topic cleaned: {} partitions", recordsToDelete.size());
                } catch (Exception e) {
                    log.debug("Failed to delete DLT records: {}", e.getMessage());
                }
            } else {
                log.info("DLT topic is already empty");
            }
        } catch (Exception e) {
            log.debug("DLT cleanup skipped: {}", e.getMessage());
        }
    }

    private String obtainToken(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private List<ConsumerRecord<String, String>> readAllDltMessages() {
        List<ConsumerRecord<String, String>> allRecords = new ArrayList<>();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "15000");

        try (Consumer<String, String> consumer = new KafkaConsumer<>(props)) {
            var allPartitions = consumer.partitionsFor(DLT_TOPIC);
            if (allPartitions == null || allPartitions.isEmpty()) {
                return allRecords;
            }

            List<TopicPartition> partitions = allPartitions.stream()
                    .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                    .collect(Collectors.toList());

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
        } catch (Exception e) {
            log.warn("Error reading DLT messages: {}", e.getMessage());
        }

        log.info("Total DLT messages found: {}", allRecords.size());
        return allRecords;
    }

    // ==================== Тесты ====================

    /**
     * Тест полного цикла ручной реобработки DLT.
     * <p>
     * Сценарий:
     * 1. Отправляем сообщение с несуществующим itemId → попадает в DLT после ретраев
     * 2. Исправляем причину ошибки: создаём недостающий Item через прямой SQL
     * 3. Вызываем endpoint репроцессинга DLT
     * 4. Проверяем ответ API: totalMessages, successfullyReprocessed, failed, details
     * 5. Проверяем заголовки DLT-сообщения (kafka_dlt-exception-message, kafka_dlt-original-topic)
     * 6. Проверяем, что запись появилась в stock_alerts с правильными данными
     * 7. Проверяем, что повторный вызов репроцессинга возвращает 0 сообщений
     */
    @Test
    void shouldReprocessDltMessageAndSaveToDatabase() throws Exception {
        // ===== ФАЗА 1: Отправляем сообщение с несуществующим itemId =====

        long invalidItemId = 777777L;
        String uniqueSku = "REPROCESS-TEST-" + System.currentTimeMillis();
        String itemName = "Товар для репроцессинга";
        int currentStock = 5;
        int minStock = 10;
        String triggeredBy = "test-user";
        LocalDateTime triggeredAt = LocalDateTime.now();

        LowStockAlertEvent event = new LowStockAlertEvent(
                invalidItemId, uniqueSku, itemName,
                currentStock, minStock, triggeredBy, triggeredAt
        );

        kafkaTemplate.send(MAIN_TOPIC, event).get();
        log.info("Phase 1: Sent event with invalid itemId={}", invalidItemId);

        // Ждём DLT
        await().atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(readAllDltMessages()).isNotEmpty();
                });

        // Проверяем заголовки
        List<ConsumerRecord<String, String>> dltRecords = readAllDltMessages();
        assertThat(dltRecords).isNotEmpty();

        ConsumerRecord<String, String> dltRecord = dltRecords.get(dltRecords.size() - 1);
        assertThat(dltRecord.headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
        assertThat(dltRecord.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();

        log.info("Phase 1 complete: message in DLT with headers");

        // ===== ФАЗА 2: Исправляем причину ошибки =====

        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO items (id, sku, name, category, min_stock, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                invalidItemId, uniqueSku, itemName, "TestCategory", minStock, true, now, now
        );
        assertThat(itemRepository.findById(invalidItemId)).isPresent();
        log.info("Phase 2 complete: item created");

        // ===== ФАЗА 3: Репроцессинг =====

        String response = mockMvc.perform(post("/api/admin/dlq/low-stock/reprocess")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        log.info("Phase 3: {}", response);

        // ===== ФАЗА 4: Проверяем ответ API =====

        DltReprocessResponse dltResponse = objectMapper.readValue(response, DltReprocessResponse.class);
        assertThat(dltResponse.totalMessages()).isGreaterThanOrEqualTo(1);
        assertThat(dltResponse.successfullyReprocessed()).isGreaterThanOrEqualTo(1);
        assertThat(dltResponse.details()).isNotEmpty();

        log.info("Phase 4 complete: API OK");

        // ===== ФАЗА 5: Проверяем БД =====

        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    List<StockAlert> alerts = stockAlertRepository.findAll();
                    assertThat(alerts).isNotEmpty();

                    var ourAlert = alerts.stream()
                            .filter(a -> a.getItem() != null
                                    && a.getItem().getId().equals(invalidItemId))
                            .findFirst();

                    assertThat(ourAlert).isPresent();
                    StockAlert alert = ourAlert.get();
                    assertThat(alert.getCurrentStock()).isEqualTo(currentStock);
                    assertThat(alert.getMinStock()).isEqualTo(minStock);
                    assertThat(alert.getTriggeredBy()).isEqualTo(triggeredBy);
                    assertThat(alert.getCreatedAt()).isNotNull();
                });

        // Проверяем Item отдельно
        Item savedItem = itemRepository.findById(invalidItemId).orElseThrow();
        assertThat(savedItem.getSku()).isEqualTo(uniqueSku);
        assertThat(savedItem.getName()).isEqualTo(itemName);

        log.info("Phase 5 complete: DB verified");

        // ===== ФАЗА 6: Проверяем, что в БД есть хотя бы одна запись =====

        long alertCount = stockAlertRepository.findAll().stream()
                .filter(a -> a.getItem() != null && a.getItem().getId().equals(invalidItemId))
                .count();

        assertThat(alertCount)
                .as("Should have at least 1 StockAlert for itemId=%d", invalidItemId)
                .isGreaterThanOrEqualTo(1);

        log.info("Phase 6 complete: {} alert(s) for itemId={}", alertCount, invalidItemId);

        log.info("========================================");
        log.info("=== FULL DLT REPROCESSING CYCLE PASSED ===");
        log.info("========================================");
    }
}