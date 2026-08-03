package com.warehouse.dto.response.movement;

import com.warehouse.entity.MovementType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO ответа, содержащий информацию о движении товара.
 *
 * @param id          идентификатор записи о движении
 * @param type        тип движения товара
 * @param quantity    количество единиц товара
 * @param performedBy имя пользователя, выполнившего операцию
 * @param createdAt   дата и время выполнения операции
 * @param warehouseId идентификатор склада
 * @param warehouseName название склада
 * @param transferId идентификатор перевода, если движение является его частью
 */
public record StockMovementHistoryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MovementType type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int quantity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String performedBy,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long warehouseId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String warehouseName,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) UUID transferId
) {
    public StockMovementHistoryResponse(
            Long id,
            MovementType type,
            int quantity,
            String performedBy,
            LocalDateTime createdAt
    ) {
        this(id, type, quantity, performedBy, createdAt, null, null, null);
    }
}
