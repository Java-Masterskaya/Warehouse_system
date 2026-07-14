package com.warehouse.dto.request.audit;

import com.warehouse.entity.AuditAction;
import com.warehouse.entity.EntityType;

import java.time.LocalDateTime;

public record AuditLogRequest(
        long userId,
        String username,
        AuditAction auditAction,
        EntityType entityType,
        long entityId,
        LocalDateTime createdAt) {
}
