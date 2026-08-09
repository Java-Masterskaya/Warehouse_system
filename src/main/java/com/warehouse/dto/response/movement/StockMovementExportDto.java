package com.warehouse.dto.response.movement;

import com.warehouse.entity.MovementType;

import java.time.LocalDateTime;
import java.util.UUID;

public record StockMovementExportDto(
        String sku,
        String name,
        String warehouseName,
        MovementType movementType,
        int quantity,
        String userName,
        LocalDateTime createdAt,
        UUID transferId
) {}
