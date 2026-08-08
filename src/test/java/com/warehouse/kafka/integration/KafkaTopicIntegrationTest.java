package com.warehouse.kafka.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.WarehouseApp;
import com.warehouse.dto.event.LowStockAlertEvent;
import com.warehouse.kafka.config.KafkaTopicProperties;
import com.warehouse.kafka.producer.KafkaStockAlertProducer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Интеграционный тест для проверки создания топика Kafka при старте.
 *
 * <p>Работает на общем Redpanda из {@link AbstractIntegrationTest}. Свой контейнер здесь
 * объявлялся, но не использовался: обращения идут через {@code getRedpanda()}, то есть
 * к общему. Лишний контейнер (к тому же другой версии образа) поднимался впустую.
 *
 * <p>{@code @DirtiesContext} тоже убран — тест только читает метаданные топика и отправляет
 * одно сообщение, бины не трогает. Он вытеснял общий Spring-контекст, заставляя
 * следующий класс поднимать его заново.
 */
@Tag("integration")
@SpringBootTest(classes = WarehouseApp.class)
class KafkaTopicIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaTopicProperties topicProperties;

    @Autowired
    private KafkaStockAlertProducer kafkaStockAlertProducer;

    private static final Long ITEM_ID = 1L;
    private static final String ITEM_SKU = "KEY-001";
    private static final String ITEM_NAME = "Тестовый товар";
    private static final int CURRENT_STOCK = 2;
    private static final int MIN_STOCK = 5;
    private static final String TRIGGERED_BY = "admin";

    /**
     * Топик создается с тремя партициями при старте приложения.
     */
    @Test
    void topicShouldBeCreatedWithThreePartitionsOnStartup() throws Exception {
        final String topicName = topicProperties.getName();
        final int partitions = topicProperties.getPartitions();
        final short replicas = topicProperties.getReplicas();

        // Arrange
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getRedpanda().getBootstrapServers());

        try (AdminClient adminClient = AdminClient.create(props)) {
            // Act & Assert
            // Ждем пока топик будет создан (максимум 10 секунд)
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                Set<String> topics = adminClient.listTopics().names().get(5, TimeUnit.SECONDS);
                assertThat(topics).contains(topicName);
            });

            // Проверяем конфигурацию топика
            var topicDescriptions = adminClient.describeTopics(Set.of(topicName))
                    .allTopicNames()
                    .get(5, TimeUnit.SECONDS);

            var topicDescription = topicDescriptions.get(topicName);

            assertThat(topicDescription.name()).isEqualTo(topicName);
            assertThat(topicDescription.partitions()).hasSize(partitions);
            assertThat(topicDescription.partitions().getFirst().replicas()).hasSize(replicas);
        }
    }

    /**
     * Отправка сообщения через KafkaTemplate не выбрасывает исключений.
     */
    @Test
    void kafkaTemplateSendShouldNotThrowException() {
        // Arrange
        LowStockAlertEvent alert = new LowStockAlertEvent(
                ITEM_ID,
                ITEM_SKU,
                ITEM_NAME,
                CURRENT_STOCK,
                MIN_STOCK,
                TRIGGERED_BY,
                LocalDateTime.now());

        // Act & Assert
        assertDoesNotThrow(() -> kafkaStockAlertProducer.sendLowStockAlert(alert));
    }
}