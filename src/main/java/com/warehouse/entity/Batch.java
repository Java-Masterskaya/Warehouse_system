package com.warehouse.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Сущность партии товара.
 * Хранит информацию о партии: товар, количество и срок годности.
 * Используется для учета товара по FEFO (first-expire-first-out).
 */
@Entity
@Table(name = "batches")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Batch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Товар, к которому относится партия.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    /**
     * Количество единиц в партии.
     */
    @Column(nullable = false)
    private int quantity;

    /**
     * Срок годности партии.
     */
    @Column(name = "expiry_date", nullable = false)
    private LocalDateTime expiryDate;

    /**
     * Время создания партии.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Версия для optimistic locking. */
    @Version
    private Long version;

    @PrePersist
    private void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
