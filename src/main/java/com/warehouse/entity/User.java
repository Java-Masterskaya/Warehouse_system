package com.warehouse.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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