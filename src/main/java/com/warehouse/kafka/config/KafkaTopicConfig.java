package com.warehouse.kafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic lowStockAlertsTopic() {
        return TopicBuilder.name("low-stock-alerts")
                .partitions(3)
                .replicas(1)
                .build();
    }
}