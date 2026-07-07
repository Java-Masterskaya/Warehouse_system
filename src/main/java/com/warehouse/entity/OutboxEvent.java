package com.warehouse.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Сущность для надежной доставки событий (Outbox pattern).
 * Гарантирует, что событие не потеряется даже при краше приложения после коммита БД.
 * Событие записывается в outbox в той же транзакции, что и бизнес-данные.
 * Отдельный релей (scheduler) опрашивает таблицу и отправляет события в Kafka.
 */
@Entity
@Table(name = "outbox")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Тип события (например, LowStockAlert).
     */
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /**
     * JSON-данные события.
     */
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    /**
     * Статус отправки.
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    /**
     * Время создания события в outbox.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Время успешной отправки в Kafka.
     */
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /**
     * Сообщение об ошибке при отправке (если есть).
     */
    @Column(name = "error_message", length = 500)
    private String errorMessage;
}
