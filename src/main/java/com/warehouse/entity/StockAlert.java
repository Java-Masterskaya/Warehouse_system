package com.warehouse.entity;

import com.warehouse.dto.event.LowStockAlertEvent;
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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;

/**
 * Запись о низком остатке товара.
 * Создаётся при получении соответствующего события.
 */
@Entity
@Table(name = "stock_alerts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class StockAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    /** Товар, по которому сработал алерт. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    Item item;

    /** Остаток на момент алерта. */
    @Column(name = "current_stock", nullable = false)
    int currentStock;

    /** Минимально допустимый остаток. */
    @Column(name = "min_stock", nullable = false)
    int minStock;

    /** Пользователь, чьё действие вызвало алерт. */
    @Column(name = "triggered_by", nullable = false, length = 100)
    String triggeredBy;

    /** Дата и время срабатывания алерта (из события). */
    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;
}
