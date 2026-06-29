package com.warehouse.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    @Column(nullable = false, length = 100)
    private String category;

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


    @Column(nullable = false, columnDefinition = "DECIMAL(19,2)")
    private BigDecimal price;

    @Column(nullable = false, columnDefinition = "DECIMAL(19,2)")
    private BigDecimal cost;

    @PrePersist
    private void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    private void preUpdate() {
        updatedAt = LocalDateTime.now();
    }


    public void setPrice(BigDecimal price) {
        if (price != null && price.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Price cannot be negative");
        }
        this.price = price != null
                ? price.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    public void setCost(BigDecimal cost) {
        if (cost != null && cost.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Cost cannot be negative");
        }
        this.cost = cost != null
                ? cost.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }
}