package com.warehouse.kafka.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.kafka.topics.low-stock")
public class KafkaTopicProperties {
    private String name;
    private int partitions;
    private short replicas;
}