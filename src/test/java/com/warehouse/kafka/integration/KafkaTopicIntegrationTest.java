package com.warehouse.kafka.integration;

import com.warehouse.WarehouseApp;
import com.warehouse.dto.event.LowStockAlert;
import com.warehouse.kafka.producer.KafkaStockAlertProducer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Tag("integration")
@Testcontainers
@DirtiesContext
@SpringBootTest(classes = WarehouseApp.class)
class KafkaTopicIntegrationTest {

    private static final String TOPIC_NAME = "low-stock-alerts";
    private static final int EXPECTED_PARTITIONS = 3;
    private static final short EXPECTED_REPLICAS = 1;

    @Container
    static RedpandaContainer redpanda = new RedpandaContainer(
            DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v24.2.1")
    );

    @Autowired
    private KafkaStockAlertProducer kafkaStockAlertProducer;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", redpanda::getBootstrapServers);
        registry.add("spring.kafka.producer.key-serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        registry.add("spring.kafka.producer.value-serializer",
                () -> "org.springframework.kafka.support.serializer.JsonSerializer");
    }

    @Test
    void topicShouldBeCreatedWithThreePartitionsOnStartup() throws Exception {
        // Arrange
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers());

        try (AdminClient adminClient = AdminClient.create(props)) {
            // Act & Assert
            // Ждем пока топик будет создан (максимум 10 секунд)
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                Set<String> topics = adminClient.listTopics().names().get(5, TimeUnit.SECONDS);
                assertThat(topics).contains(TOPIC_NAME);
            });

            // Проверяем конфигурацию топика
            var topicDescriptions = adminClient.describeTopics(Set.of(TOPIC_NAME))
                    .allTopicNames()
                    .get(5, TimeUnit.SECONDS);

            var topicDescription = topicDescriptions.get(TOPIC_NAME);

            assertThat(topicDescription.name()).isEqualTo(TOPIC_NAME);
            assertThat(topicDescription.partitions()).hasSize(EXPECTED_PARTITIONS);
            assertThat(topicDescription.partitions().getFirst().replicas()).hasSize(EXPECTED_REPLICAS);
        }
    }

    @Test
    void kafkaTemplateSendShouldNotThrowException() {
        // Arrange
        LowStockAlert alert = new LowStockAlert(1L, "Тестовый товар", 2, 5);

        // Act & Assert
        assertDoesNotThrow(() -> kafkaStockAlertProducer.sendLowStockAlert(alert));
    }

    @Test
    void shouldSendMessageSuccessfullyToTopic() {
        // Arrange
        Long itemId = 101L;
        LowStockAlert alert = new LowStockAlert(itemId, "Ноутбук Dell", 3, 10);

        // Act & Assert - не должно выбрасывать исключение
        assertDoesNotThrow(() -> kafkaStockAlertProducer.sendLowStockAlert(alert));
    }
}