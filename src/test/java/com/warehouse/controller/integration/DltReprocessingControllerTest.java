package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.WarehouseApp;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockAlert;
import com.warehouse.entity.User;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockAlertRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Slf4j
@AutoConfigureMockMvc
@Tag("integration")
@SpringBootTest(classes = WarehouseApp.class)
@ActiveProfiles("test")
@Testcontainers
class DltReprocessingControllerTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("app.kafka.topics.low-stock.reprocess-batch-size", () -> "5");
    }

    private static final String MAIN_TOPIC = "low-stock-alerts";
    private static final String DLT_TOPIC = "low-stock-alerts.DLT";
    private static final String REPROCESS_GROUP_ID = "dlt-reprocess-service";

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

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private String adminToken;
    private String userToken;
    private Item testItem;
    private Long testItemId;
    private Category testCategory;

    @BeforeEach
    void setUp() throws Exception {
        log.info("Test setup...");

        // Удаляем в правильном порядке (сначала зависимые таблицы), чтобы избежать ошибок внешних ключей
        // Важно: static Testcontainers живут между запусками, поэтому leftover data
        // из предыдущих тестов нарушает ассерты и уникальные индексы
        jdbcTemplate.update("DELETE FROM stock_alerts");
        jdbcTemplate.update("DELETE FROM stock_movements");
        jdbcTemplate.update("DELETE FROM batches");
        jdbcTemplate.update("DELETE FROM outbox");

        jdbcTemplate.update("DELETE FROM stock");
        jdbcTemplate.update("DELETE FROM items");
        jdbcTemplate.update("DELETE FROM categories");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM reserves");

        // Очищаем последовательно, чтобы убрать дубликаты (DELETE + INSERT работает быстрее)
        jdbcTemplate.update("ALTER SEQUENCE stock_alerts_id_seq RESTART WITH 1");
        jdbcTemplate.update("ALTER SEQUENCE items_id_seq RESTART WITH 1");
        jdbcTemplate.update("ALTER SEQUENCE categories_id_seq RESTART WITH 1");
        jdbcTemplate.update("ALTER SEQUENCE users_id_seq RESTART WITH 1");

        resetConsumerGroupOffsets();

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

        testCategory = categoryRepository.save(
                Category.builder()
                        .name("Категория")
                        .build()
        );

        testItem = Item.builder()
                .sku("SKU-DLT-" + System.currentTimeMillis())
                .name("Тестовый товар DLT")
                .category(testCategory)
                .minStock(10)
                .active(true)
                .build();
        testItem = itemRepository.save(testItem);
        testItemId = testItem.getId();

        Stock stock = Stock.builder()
                .item(testItem)
                .warehouse(defaultWarehouse())
                .quantity(5)
                .build();
        stockRepository.save(stock);

        // Создаем партию для начального остатка (чтобы FEFO могла работать)
        com.warehouse.entity.Batch batch = new com.warehouse.entity.Batch();
        batch.setItem(testItem);
        batch.setWarehouse(defaultWarehouse());
        batch.setQuantity(5);
        batch.setExpiryDate(LocalDateTime.now().plusDays(365)); // Далекий срок годности
        batchRepository.save(batch);

        adminToken = obtainToken("admin", "secret");
        userToken = obtainToken("testuser", "password");

        log.info("Setup completed");
    }

    // ==================== Helpers ====================

    private AdminClient createAdminClient() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getRedpanda().getBootstrapServers());
        return AdminClient.create(props);
    }

    /**
     * Удаляет и пересоздаёт DLT топик для гарантии чистого состояния.
     */
    private void deleteAndRecreateDltTopic() {
        try (AdminClient adminClient = createAdminClient()) {
            var existingTopics = adminClient.listTopics().names().get();

            if (existingTopics.contains(DLT_TOPIC)) {
                log.debug("Deleting existing DLT topic: {}", DLT_TOPIC);

                // Сначала удаляем consumer group, чтобы сбросить оффсеты
                try {
                    adminClient.deleteConsumerGroups(Collections.singletonList(REPROCESS_GROUP_ID)).all().get();
                    log.debug("Deleted consumer group: {}", REPROCESS_GROUP_ID);
                } catch (Exception e) {
                    log.debug("Could not delete consumer group (may not exist): {}", e.getMessage());
                }

                // Удаляем топик
                adminClient.deleteTopics(Collections.singletonList(DLT_TOPIC)).all().get();

                // Ждём, пока топик действительно удалится (увеличиваем таймаут)
                await().atMost(30, TimeUnit.SECONDS)
                        .pollInterval(500, TimeUnit.MILLISECONDS)
                        .until(() -> {
                            try {
                                var topics = adminClient.listTopics().names().get();
                                boolean deleted = !topics.contains(DLT_TOPIC);
                                if (deleted) {
                                    log.debug("DLT topic successfully deleted");
                                }
                                return deleted;
                            } catch (Exception e) {
                                log.debug("Error checking topic deletion: {}", e.getMessage());
                                return false;
                            }
                        });
                log.debug("DLT topic deleted");
            }

            // Создаём топик с 1 партицией
            NewTopic newTopic = new NewTopic(DLT_TOPIC, 1, (short) 1);
            adminClient.createTopics(Collections.singletonList(newTopic)).all().get();
            log.debug("Created DLT topic: {} with 1 partition", DLT_TOPIC);

            // Ждём создания топика
            await().atMost(10, TimeUnit.SECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .until(() -> {
                        try {
                            var topics = adminClient.listTopics().names().get();
                            return topics.contains(DLT_TOPIC);
                        } catch (Exception e) {
                            return false;
                        }
                    });

            // Дополнительная задержка для стабилизации
            Thread.sleep(1000);

        } catch (Exception e) {
            throw new RuntimeException("Failed to recreate DLT topic", e);
        }
    }

    /**
     * Сбрасывает offset consumer group в начало для чистого старта.
     */
    private void resetConsumerGroupOffsets() {
        try (AdminClient adminClient = createAdminClient()) {
            var existingTopics = adminClient.listTopics().names().get();
            if (!existingTopics.contains(DLT_TOPIC)) {
                return;
            }

            var topicDesc = adminClient.describeTopics(Collections.singletonList(DLT_TOPIC))
                    .allTopicNames().get();
            int partitionCount = topicDesc.get(DLT_TOPIC).partitions().size();

            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets = new HashMap<>();
            for (int i = 0; i < partitionCount; i++) {
                offsets.put(new TopicPartition(DLT_TOPIC, i),
                        new org.apache.kafka.clients.consumer.OffsetAndMetadata(0));
            }

            adminClient.alterConsumerGroupOffsets(REPROCESS_GROUP_ID, offsets).all().get();
            log.debug("Reset offsets for group {} to beginning", REPROCESS_GROUP_ID);
        } catch (Exception e) {
            log.trace("Could not reset offsets (group may not exist yet): {}", e.getMessage());
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
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    // ==================== DLT Reading ====================

    /**
     * Читает все сообщения из DLT (с earliest) для проверки наличия.
     *
     * @return список записей из DLT
     */
    private List<ConsumerRecord<String, String>> readAllDltMessages() {
        List<ConsumerRecord<String, String>> allRecords = new ArrayList<>();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, getRedpanda().getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "15000");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");

        Consumer<String, String> consumer = null;
        try {
            consumer = new KafkaConsumer<>(props);
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
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                if (records.isEmpty()) {
                    emptyPolls++;
                } else {
                    emptyPolls = 0;
                    records.forEach(allRecords::add);
                }
            }
        } catch (Exception e) {
            log.trace("DLT read error: {}", e.getMessage());
        } finally {
            if (consumer != null) {
                try {
                    consumer.close(Duration.ofSeconds(2));
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        log.debug("DLT messages read: {}", allRecords.size());
        return allRecords;
    }

    // ==================== Admin API Checks ====================

    /**
     * Проверяет, что consumer group обработала все сообщения в DLT.
     *
     * @return true если все сообщения обработаны, false в противном случае
     */
    private boolean isDltFullyProcessed() {
        try (AdminClient adminClient = createAdminClient()) {
            var topicDesc = adminClient.describeTopics(Collections.singletonList(DLT_TOPIC))
                    .allTopicNames().get();
            int partitionCount = topicDesc.get(DLT_TOPIC).partitions().size();

            List<TopicPartition> partitions = IntStream.range(0, partitionCount)
                    .mapToObj(i -> new TopicPartition(DLT_TOPIC, i))
                    .collect(Collectors.toList());

            // Получаем end offsets
            Map<TopicPartition, OffsetSpec> latestSpecs = partitions.stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            var endOffsets = adminClient.listOffsets(latestSpecs).all().get();

            // Получаем committed offsets для reprocess group
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed;
            try {
                committed = adminClient.listConsumerGroupOffsets(REPROCESS_GROUP_ID)
                        .partitionsToOffsetAndMetadata().get();
            } catch (Exception e) {
                log.debug("Group {} has no committed offsets yet", REPROCESS_GROUP_ID);
                return false;
            }

            for (TopicPartition tp : partitions) {
                long endOffset = endOffsets.get(tp).offset();
                Long committedOffset;
                if (committed.containsKey(tp)) {
                    committedOffset = committed.get(tp).offset();
                } else {
                    committedOffset = null;
                }

                log.debug("Partition {}: endOffset={}, committedOffset={}",
                        tp.partition(), endOffset, committedOffset);

                if (committedOffset == null || committedOffset < endOffset) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.warn("Error checking DLT processing status: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Возвращает количество необработанных сообщений в DLT.
     *
     * @return количество необработанных сообщений
     */
    private long getUnprocessedDltMessageCount() {
        try (AdminClient adminClient = createAdminClient()) {
            var topicDesc = adminClient.describeTopics(Collections.singletonList(DLT_TOPIC))
                    .allTopicNames().get();
            int partitionCount = topicDesc.get(DLT_TOPIC).partitions().size();

            List<TopicPartition> partitions = IntStream.range(0, partitionCount)
                    .mapToObj(i -> new TopicPartition(DLT_TOPIC, i))
                    .collect(Collectors.toList());

            Map<TopicPartition, OffsetSpec> latestSpecs = partitions.stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            var endOffsets = adminClient.listOffsets(latestSpecs).all().get();

            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed;
            try {
                committed = adminClient.listConsumerGroupOffsets(REPROCESS_GROUP_ID)
                        .partitionsToOffsetAndMetadata().get();
            } catch (Exception e) {
                // Группа ещё не существует — все сообщения необработаны
                return partitions.stream()
                        .mapToLong(tp -> endOffsets.get(tp).offset())
                        .sum();
            }

            long unprocessed = 0;
            for (TopicPartition tp : partitions) {
                long endOffset = endOffsets.get(tp).offset();
                long committedOffset;
                if (committed.containsKey(tp)) {
                    committedOffset = committed.get(tp).offset();
                } else {
                    committedOffset = 0;
                }
                unprocessed += Math.max(0, endOffset - committedOffset);
            }

            return unprocessed;
        } catch (Exception e) {
            log.warn("Error counting unprocessed messages: {}", e.getMessage());
            return -1;
        }
    }

    // ==================== Tests ====================

    @Test
    void shouldReprocessDltMessageAndSaveToDatabase() throws Exception {
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
        log.info("Sent event with invalid itemId={}", invalidItemId);

        await().atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(readAllDltMessages()).isNotEmpty();
                });

        List<ConsumerRecord<String, String>> dltRecords = readAllDltMessages();
        assertThat(dltRecords).isNotEmpty();

        ConsumerRecord<String, String> dltRecord = dltRecords.get(dltRecords.size() - 1);
        assertThat(dltRecord.headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
        assertThat(dltRecord.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();

        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO items (id, sku, name, category_id, min_stock, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                invalidItemId, uniqueSku, itemName, testCategory.getId(), minStock, true, now, now
        );
        assertThat(itemRepository.findById(invalidItemId)).isPresent();
        log.info("Item created");

        mockMvc.perform(post("/api/admin/dlq/low-stock/reprocess")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted());

        log.info("DLT reprocessing started");

        await().atMost(15, TimeUnit.SECONDS)
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

        // Проверяем что DLT пуст (записи удалены после репроцессинга)
        await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(readAllDltMessages())
                            .as("DLT should be empty after reprocessing and deletion")
                            .isEmpty();
                });

        Item savedItem = itemRepository.findById(invalidItemId).orElseThrow();
        assertThat(savedItem.getSku()).isEqualTo(uniqueSku);
        assertThat(savedItem.getName()).isEqualTo(itemName);

        log.info("=== DLT reprocessing test PASSED ===");
    }

    @Test
    void shouldDeleteMessagesFromDltAfterReprocessing() throws Exception {
        log.info("=== Starting batch reprocessing test ===");

        int totalMessages = 10;
        String uniqueSkuPrefix = "BATCH-TEST-" + System.currentTimeMillis();

        // Phase 1: Send messages with pauses
        for (int i = 0; i < totalMessages; i++) {
            long invalidItemId = 888888L + i;
            String uniqueSku = uniqueSkuPrefix + "-" + i;

            LowStockAlertEvent event = new LowStockAlertEvent(
                    invalidItemId,
                    uniqueSku,
                    "Товар для батча " + i,
                    5, 10,
                    "test-user", LocalDateTime.now()
            );

            kafkaTemplate.send(MAIN_TOPIC, event).get();

            if (i % 5 == 0 && i > 0) {
                Thread.sleep(200);
            }
        }
        log.info("Sent {} messages", totalMessages);

        // Phase 2: Wait for all messages in DLT
        AtomicInteger lastDltCount = new AtomicInteger(0);
        await().atMost(120, TimeUnit.SECONDS)
                .pollInterval(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var messages = readAllDltMessages();
                    int count = messages.size();
                    lastDltCount.set(count);
                    log.info("DLT progress: {}/{}", count, totalMessages);
                    assertThat(count)
                            .as("Expected %d messages in DLT, found %d", totalMessages, count)
                            .isEqualTo(totalMessages);
                });

        log.info("All {} messages in DLT", totalMessages);

        // Phase 3: Create missing items
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < totalMessages; i++) {
            long invalidItemId = 888888L + i;
            String uniqueSku = uniqueSkuPrefix + "-" + i;
            jdbcTemplate.update(
                    "INSERT INTO items (id, sku, name, category_id, min_stock, is_active, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    invalidItemId, uniqueSku, "Товар для батча " + i, testCategory.getId(), 10, true, now, now
            );
        }
        log.info("Created {} items", totalMessages);

        // Phase 4: First reprocessing call (batch size = 5)
        mockMvc.perform(post("/api/admin/dlq/low-stock/reprocess")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted());

        log.info("First reprocessing call (batch=5)");

        // Wait for first 5 messages to be processed
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    List<StockAlert> alerts = stockAlertRepository.findAll();
                    long count = alerts.stream()
                            .filter(a -> a.getItem() != null && a.getItem().getId() >= 888888L)
                            .count();
                    assertThat(count)
                            .as("Expected 5 StockAlerts, found %d", count)
                            .isEqualTo(5);
                });

        // Check: 5 messages remain in DLT (batch size = 5, processed first 5)
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    int remaining = readAllDltMessages().size();
                    assertThat(remaining)
                            .as("Expected 5 remaining messages in DLT, found %d", remaining)
                            .isEqualTo(5);
                });

        log.info("Batch 1 done: 5 processed, 5 remain");

        // Phase 5: Second reprocessing call
        mockMvc.perform(post("/api/admin/dlq/low-stock/reprocess")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted());

        log.info("Second reprocessing call");

        // Wait for remaining 5 messages
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    List<StockAlert> alerts = stockAlertRepository.findAll();
                    long count = alerts.stream()
                            .filter(a -> a.getItem() != null && a.getItem().getId() >= 888888L)
                            .count();
                    assertThat(count)
                            .as("Expected %d total StockAlerts, found %d", totalMessages, count)
                            .isEqualTo(totalMessages);
                });

        log.info("Batch 2 done: all {} processed", totalMessages);

        // Phase 6: Verify DLT is empty after second reprocessing and deletion
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var messages = readAllDltMessages();
                    assertThat(messages)
                            .as("DLT should be empty after second reprocessing and deletion, found %d messages",
                                    messages.size())
                            .isEmpty();
                });

        log.info("=== Batch reprocessing test PASSED ===");
    }

    @Test
    void shouldNotCreateDuplicateStockAlertsWhenReprocessingSameMessageMultipleTimes() throws Exception {
        log.info("=== Starting duplicate prevention test ===");

        long itemId = 999999L;
        String uniqueSku = "DUP-TEST-" + System.currentTimeMillis();
        String itemName = "Товар для проверки дубликатов";
        int currentStock = 5;
        int minStock = 10;
        String triggeredBy = "test-user";
        LocalDateTime triggeredAt = LocalDateTime.now();

        // Создаем item ДО отправки события
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO items (id, sku, name, category_id, min_stock, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                itemId, uniqueSku, itemName, testCategory.getId(), minStock, true, now, now
        );
        log.info("Item created with id={}", itemId);

        // Отправляем событие в main topic (создаст первый StockAlert)
        LowStockAlertEvent event = new LowStockAlertEvent(
                itemId, uniqueSku, itemName,
                currentStock, minStock, triggeredBy, triggeredAt
        );
        kafkaTemplate.send(MAIN_TOPIC, event).get();
        log.info("Sent event to main topic");

        // Ждем первый StockAlert
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    List<StockAlert> alerts = stockAlertRepository.findAll();
                    long count = alerts.stream()
                            .filter(a -> a.getItem() != null && a.getItem().getId().equals(itemId))
                            .count();
                    assertThat(count).isEqualTo(1);
                });
        log.info("First StockAlert created");

        // Отправляем ДУБЛИКАТ напрямую в DLT
        LowStockAlertEvent duplicateEvent = new LowStockAlertEvent(
                itemId, uniqueSku, itemName,
                currentStock, minStock, triggeredBy, triggeredAt
        );
        kafkaTemplate.send(DLT_TOPIC, duplicateEvent).get();
        log.info("Sent duplicate event to DLT");

        // Проверяем, что сообщение в DLT
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    List<ConsumerRecord<String, String>> dltMessages = readAllDltMessages();
                    assertThat(dltMessages).isNotEmpty();
                });

        // Запускаем репроцессинг
        mockMvc.perform(post("/api/admin/dlq/low-stock/reprocess")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted());
        log.info("Reprocessing triggered");

        // Ждем завершения репроцессинга (появление второго вызова не должно создать дубликат)
        Thread.sleep(5000);

        // ФИНАЛЬНАЯ ПРОВЕРКА: должен быть ровно ОДИН StockAlert
        List<StockAlert> allAlerts = stockAlertRepository.findAll();
        long duplicateCount = allAlerts.stream()
                .filter(a -> a.getItem() != null && a.getItem().getId().equals(itemId))
                .count();

        assertThat(duplicateCount)
                .as("Should have exactly 1 StockAlert for itemId=%d, but found %d. Duplicate prevention failed!",
                        itemId, duplicateCount)
                .isEqualTo(1);

        log.info("=== Duplicate prevention test PASSED ===");
    }

    @Test
    void shouldHandleDuplicateInBatchCorrectly() throws Exception {
        log.info("=== Starting batch duplicate test ===");

        int batchSize = 5;
        long baseItemId = 777777L;
        String uniqueSkuPrefix = "BATCH-DUP-" + System.currentTimeMillis();
        LocalDateTime triggeredAt = LocalDateTime.now();

        // Создаем items (4 штуки)
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 4; i++) {
            long itemId = baseItemId + i;
            String sku = uniqueSkuPrefix + "-" + i;
            jdbcTemplate.update(
                    "INSERT INTO items (id, sku, name, category_id, min_stock, is_active, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    itemId, sku, "Batch item " + i, testCategory.getId(), 10, true, now, now
            );
        }
        log.info("Created 4 items");

        // Отправляем 2 дубликата для item[0] + 3 уникальных для остальных = 5 сообщений всего
        for (int i = 0; i < 2; i++) {
            LowStockAlertEvent event = new LowStockAlertEvent(
                    baseItemId, uniqueSkuPrefix + "-0", "Batch item 0",
                    5, 10, "test-user", triggeredAt
            );
            kafkaTemplate.send(DLT_TOPIC, event).get();
            Thread.sleep(100);
        }
        log.info("Sent 2 duplicate events for itemId={} to DLT", baseItemId);

        for (int i = 1; i < 4; i++) {
            LowStockAlertEvent event = new LowStockAlertEvent(
                    baseItemId + i, uniqueSkuPrefix + "-" + i, "Batch item " + i,
                    5, 10, "test-user", triggeredAt
            );
            kafkaTemplate.send(DLT_TOPIC, event).get();
        }
        log.info("Sent 3 unique events to DLT");

        // Проверяем DLT сообщения (должно быть ровно 5)
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    int dltCount = readAllDltMessages().size();
                    log.info("DLT message count: {}", dltCount);
                    assertThat(dltCount).isEqualTo(5);
                });

        // Запускаем репроцессинг (batch size = 5, обработает все за один раз)
        mockMvc.perform(post("/api/admin/dlq/low-stock/reprocess")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted());
        log.info("Reprocessing triggered");

        // Ждем все StockAlerts (должно быть ровно 4 - по одному на каждый уникальный item)
        await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    List<StockAlert> alerts = stockAlertRepository.findAll();
                    long count = alerts.stream()
                            .filter(a -> a.getItem() != null
                                    && a.getItem().getId() >= baseItemId
                                    && a.getItem().getId() < baseItemId + 4)
                            .count();
                    log.info("StockAlert count: {}", count);
                    assertThat(count).isEqualTo(4); // 4 уникальных item'а
                });

        // Финальная проверка на дубликаты
        List<StockAlert> allAlerts = stockAlertRepository.findAll();

        // Item с 2 дубликатами должен иметь ровно 1 StockAlert
        long duplicateItemCount = allAlerts.stream()
                .filter(a -> a.getItem() != null && a.getItem().getId().equals(baseItemId))
                .count();
        assertThat(duplicateItemCount)
                .as("Item with 2 duplicates should have exactly 1 StockAlert, found %d", duplicateItemCount)
                .isEqualTo(1);

        // Остальные 3 item'а должны иметь по 1 StockAlert
        for (int i = 1; i < 4; i++) {
            long itemId = baseItemId + i;
            long itemCount = allAlerts.stream()
                    .filter(a -> a.getItem() != null && a.getItem().getId().equals(itemId))
                    .count();
            assertThat(itemCount)
                    .as("Item %d should have exactly 1 StockAlert, found %d", itemId, itemCount)
                    .isEqualTo(1);
        }

        log.info("=== Batch duplicate test PASSED ===");
    }

    @Test
    void shouldNotCreateDuplicatesWhenReprocessingCalledMultipleTimes() throws Exception {
        log.info("=== Starting multiple reprocessing calls test ===");

        long itemId = 888888L;
        String uniqueSku = "MULTI-DUP-" + System.currentTimeMillis();
        String itemName = "Товар для множественного репроцессинга";
        int currentStock = 5;
        int minStock = 10;
        String triggeredBy = "test-user";
        LocalDateTime triggeredAt = LocalDateTime.now();

        // Отправляем событие в main topic БЕЗ создания item (уйдет в DLT)
        LowStockAlertEvent event = new LowStockAlertEvent(
                itemId, uniqueSku, itemName,
                currentStock, minStock, triggeredBy, triggeredAt
        );
        kafkaTemplate.send(MAIN_TOPIC, event).get();
        log.info("Sent event without item to main topic");

        // Ждем сообщение в DLT
        await().atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(readAllDltMessages()).isNotEmpty();
                });
        log.info("Message in DLT confirmed");

        // Создаем item
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO items (id, sku, name, category_id, min_stock, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                itemId, uniqueSku, itemName, testCategory.getId(), minStock, true, now, now
        );
        log.info("Item created");

        // Вызываем репроцессинг НЕСКОЛЬКО РАЗ
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/admin/dlq/low-stock/reprocess")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isAccepted());
            log.info("Reprocess call #{}", i + 1);

            // Даем время на обработку между вызовами
            Thread.sleep(2000);
        }

        // Ждем завершения обработки и появления StockAlert
        await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    List<StockAlert> alerts = stockAlertRepository.findAll();
                    long count = alerts.stream()
                            .filter(a -> a.getItem() != null && a.getItem().getId().equals(itemId))
                            .count();
                    assertThat(count)
                            .as("Expected at least 1 StockAlert, found %d", count)
                            .isGreaterThanOrEqualTo(1);
                });

        // Дополнительная пауза чтобы убедиться, что дубликаты не создались
        Thread.sleep(3000);

        // ФИНАЛЬНАЯ ПРОВЕРКА: должен быть ровно ОДИН StockAlert
        List<StockAlert> allAlerts = stockAlertRepository.findAll();
        long count = allAlerts.stream()
                .filter(a -> a.getItem() != null && a.getItem().getId().equals(itemId))
                .count();

        assertThat(count)
                .as("Expected exactly 1 StockAlert after 3 reprocess calls, but found %d. Duplicates detected!", count)
                .isEqualTo(1);

        log.info("=== Multiple reprocessing calls test PASSED ===");
    }

    private void verifyDltRemainingCount(int callNumber, int expectedCount) {
        int timeoutSeconds;
        if (expectedCount == 0) {
            timeoutSeconds = 15;
        } else {
            timeoutSeconds = 10;
        }

        await().atMost(timeoutSeconds, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var messages = readAllDltMessages();
                    if (expectedCount == 0) {
                        assertThat(messages)
                                .as("After call %d: DLT should be empty, found %d messages",
                                        callNumber, messages.size())
                                .isEmpty();
                    } else {
                        int remaining = messages.size();
                        assertThat(remaining)
                                .as("After call %d: expected %d messages in DLT, found %d",
                                        callNumber, expectedCount, remaining)
                                .isEqualTo(expectedCount);
                    }
                });
    }

}
