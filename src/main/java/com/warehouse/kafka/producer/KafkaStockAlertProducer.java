package com.warehouse.kafka.producer;

import com.warehouse.dto.event.LowStockAlert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KafkaStockAlertProducer implements KafkaProducerService {
    private static final String TOPIC_NAME = "low-stock-alerts";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaStockAlertProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Отправляет уведомление о низком остатке товара в топик Kafka/Redpanda.
     *
     * <p>Метод использует механизм повторных попыток (retry) с экспоненциальной задержкой:
     * <ul>
     *   <li>Максимальное количество попыток: 3 (по умолчанию)</li>
     *   <li>Начальная задержка: 1000 мс (1 секунда)</li>
     *   <li>Множитель задержки: 2 (экспоненциальный backoff: 1с → 2с → 4с)</li>
     * </ul>
     *
     * <p>Ключом сообщения выступает {@code itemId}, что гарантирует попадание всех уведомлений
     * по одному товару в одну и ту же партицию, сохраняя порядок событий.
     *
     * <p>Метод использует блокирующий вызов {@code .get()} для получения результата отправки,
     * что позволяет механизму retry корректно перехватывать и обрабатывать исключения.
     *
     * @param alert DTO с данными уведомления о низком остатке
     * @throws RuntimeException если после всех попыток сообщение не удалось отправить
     *                          (например, брокер недоступен или произошла ошибка сериализации)
     * @see org.springframework.retry.annotation.Retryable
     * @see org.springframework.kafka.core.KafkaTemplate
     */
    @Retryable(
            retryFor = {Exception.class},
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public void sendLowStockAlert(LowStockAlert alert) {
        log.debug("Received low stock alert: {}", alert);
        try {
            SendResult<String, Object> result = kafkaTemplate.send(
                    TOPIC_NAME,
                    String.valueOf(alert.itemId()),
                    alert
            ).get();
            log.info("Successfully sent to topic {} для itemId {}. Partition: {}, Offset: {}",
                    TOPIC_NAME, alert.itemId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        } catch (Exception e) {
            throw new RuntimeException("Error while sending message to Kafka", e);
        }
    }
}