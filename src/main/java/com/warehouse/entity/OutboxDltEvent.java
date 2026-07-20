package com.warehouse.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Сущность для записей Dead Letter Table (outbox_dlt).
 * Хранит события outbox, которые превысили лимит ретраев
 * или имеют битый payload (deserialization error).
 *
 * События в DLT не обрабатываются автоматически — требуют
 * ручного или административного репроцессинга.
 */
@Entity
@Table(name = "outbox_dlt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxDltEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_outbox_id", nullable = false, unique = true)
    private Long originalOutboxId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "permanent_failure_reason", nullable = false, length = 100)
    private String permanentFailureReason;

    @Column(name = "dlt_created_at", nullable = false, updatable = false)
    private LocalDateTime dltCreatedAt;

    @PrePersist
    public void prePersist() {
        if (dltCreatedAt == null) {
            dltCreatedAt = LocalDateTime.now();
        }
    }
}
