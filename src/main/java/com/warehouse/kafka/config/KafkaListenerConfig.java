package com.warehouse.kafka.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.function.BiFunction;

import static org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class KafkaListenerConfig {

    private final KafkaTopicProperties topicProperties;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.consumer.concurrency}")
    private int concurrency;

    @Value("${app.kafka.retry.initial-interval-ms}")
    private long initialIntervalMs;

    @Value("${app.kafka.retry.multiplier}")
    private double multiplier;

    @Value("${app.kafka.retry.max-interval-ms}")
    private long maxIntervalMs;

    @Value("${app.kafka.retry.max-attempts}")
    private int maxAttempts;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(RECORD);

        var dltTopicName = topicProperties.getName() + ".DLT";
        factory.setCommonErrorHandler(errorHandler(dltTopicName));

        return factory;
    }

    private CommonErrorHandler errorHandler(String dltTopicName) {
        var backOff = new ExponentialBackOff(initialIntervalMs, multiplier);
        backOff.setMaxInterval(maxIntervalMs);
        backOff.setMaxAttempts(maxAttempts);

        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> topicResolver =
                (cr, ex) -> {
                    log.error("Sending to DLT: topic={}, partition={}, offset={}",
                            cr.topic(), cr.partition(), cr.offset(), ex);
                    return new TopicPartition(dltTopicName, cr.partition());
                };

        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate, topicResolver);

        var handler = new DefaultErrorHandler(recoverer, backOff);

        handler.addNotRetryableExceptions(
                SerializationException.class,
                com.fasterxml.jackson.core.JsonParseException.class,
                com.fasterxml.jackson.databind.JsonMappingException.class,
                org.springframework.messaging.converter.MessageConversionException.class
        );

        handler.addRetryableExceptions(
                org.springframework.dao.DataAccessException.class,
                java.util.concurrent.TimeoutException.class
        );

        return handler;
    }
}