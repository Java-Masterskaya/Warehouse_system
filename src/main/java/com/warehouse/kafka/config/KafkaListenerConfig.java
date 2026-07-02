package com.warehouse.kafka.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
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

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(RECORD);

        // Настройка errorHandler с ретраями и DLT
        var dltTopicName = topicProperties.getName() + ".DLT";

        factory.setCommonErrorHandler(errorHandler(dltTopicName));

        return factory;
    }

    private CommonErrorHandler errorHandler(String dltTopicName) {
        // Экспоненциальный бэкофф: начальный интервал 1 сек, множитель 2, макс. интервал 10 сек, макс. попыток 3
        var backOff = new ExponentialBackOff(1000L, 2);
        backOff.setMaxInterval(10000L);
        backOff.setMaxAttempts(3);

        // DeadLetterPublishingRecoverer для отправки в DLT — стандартный, без кастомных заголовков
        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> topicResolver =
                (cr, ex) -> {
                    log.error("Sending to DLT: topic={}, partition={}, offset={}",
                            cr.topic(), cr.partition(), cr.offset(), ex);
                    return new TopicPartition(dltTopicName, cr.partition());
                };

        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate, topicResolver);

        // DefaultErrorHandler с recoverer и backOff
        var handler = new DefaultErrorHandler(recoverer, backOff);

        // Добавляем исключения, которые НЕ должны retry-аться (битый JSON)
        handler.addNotRetryableExceptions(
                SerializationException.class,
                com.fasterxml.jackson.core.JsonParseException.class,
                com.fasterxml.jackson.databind.JsonMappingException.class,
                org.springframework.messaging.converter.MessageConversionException.class
        );

        // Добавляем исключения, которые должны retry-аться (временные ошибки)
        handler.addRetryableExceptions(
                org.springframework.dao.DataAccessException.class,
                java.util.concurrent.TimeoutException.class
        );

        return handler;
    }
}