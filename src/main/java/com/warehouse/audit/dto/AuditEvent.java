package com.warehouse.audit.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.warehouse.audit.entity.AuditAction;
import com.warehouse.audit.entity.EntityType;

public record AuditEvent(
        AuditAction action,
        EntityType entityType,
        Long entityId,
        Long userId,
        String username,
        JsonNode oldValue,
        JsonNode newValue
) {}