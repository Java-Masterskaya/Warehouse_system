package com.warehouse.audit.entity;

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

import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Immutable
@Table(name = "audit_log")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username")
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "audit_action", nullable = false)
    private AuditAction auditAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value")
    private JsonNode oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value")
    private JsonNode newValue;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}