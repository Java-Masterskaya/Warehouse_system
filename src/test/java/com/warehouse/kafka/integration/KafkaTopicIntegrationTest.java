package com.warehouse.kafka.integration;

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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
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

    @Container
    static RedpandaContainer redpanda = new RedpandaContainer(
            DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v24.2.1")
    );

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
        final String topicName = topicProperties.getName();
        final int partitions = topicProperties.getPartitions();
        final short replicas = topicProperties.getReplicas();

        // Arrange
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers());

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