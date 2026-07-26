package com.warehouse.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "reserves")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Reservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Текущее состояние товара. */
    @ManyToOne
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    /** Пользователь, выполнивший операцию. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Количество забронированных товаров. */
    private int quantity;

    /** Статус резервирования. */
    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    /** Временные штампы создания и срок годности. */
    private LocalDateTime createdAt;
    private LocalDateTime expiredAt;

    @PrePersist
    private void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
