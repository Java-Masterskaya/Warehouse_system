package com.warehouse.kafka.config;

import org.apache.kafka.common.serialization.StringDeserializer;
import com.warehouse.dto.event.LowStockAlertEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import static org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class KafkaListenerConfig {

    private final KafkaTopicProperties topicProperties;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

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
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.warehouse.dto.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, LowStockAlertEvent.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaConsumerFactory<>(props);
    }

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
                org.springframework.dao.TransientDataAccessResourceException.class,
                org.springframework.transaction.TransactionSystemException.class,
                java.util.concurrent.TimeoutException.class,
                java.net.SocketTimeoutException.class,
                java.net.ConnectException.class,
                org.springframework.web.client.ResourceAccessException.class,
                java.util.concurrent.RejectedExecutionException.class
        );

        return handler;
    }
}