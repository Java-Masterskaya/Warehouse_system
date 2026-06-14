package com.warehouse.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;

/**
 * Сущность пользователя системы.
 * Хранит информацию о пользователе: логин, роль и статус активности.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    /**
     * Логин пользователя (уникальный).
     */
    @Column(nullable = false, unique = true, length = 100)
    String username;

    /**
     * Пароль пользователя.
     */
    @Column(nullable = false)
    String password;

    /**
     * Роль пользователя (ADMIN или USER).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    Role role;

    /**
     * Флаг активности пользователя.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    boolean active = true;

    /**
     * Время создания пользователя.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;

    @PrePersist
    private void prePersist() {
        createdAt = LocalDateTime.now();
    }
}