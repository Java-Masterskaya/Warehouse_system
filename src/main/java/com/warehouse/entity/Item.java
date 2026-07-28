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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Сущность товара.
 * Хранит основную информацию о товаре: артикул, название, категорию и минимальный остаток.
 */
@Entity
@Table(name = "items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Артикул товара.
     */
    @Column(nullable = false, unique = true, length = 100)
    private String sku;

    /**
     * Название товара.
     */
    @Column(nullable = false)
    private String name;

    /**
     * Категория товара.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /**
     * Минимально допустимый остаток для отслеживания.
     */
    @Column(name = "min_stock", nullable = false)
    private int minStock;

    /**
     * Флаг активности товара.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * Время создания товара.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Время последнего обновления.
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(columnDefinition = "DECIMAL(19,2)")
    private BigDecimal price;

    @Column(columnDefinition = "DECIMAL(19,2)")
    private BigDecimal cost;

    /**
     * Штрихкод товара.
     */
    @Column(length = 255)
    private String barcode;

    @PrePersist
    private void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    private void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}