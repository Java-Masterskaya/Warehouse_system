package com.warehouse.dto.response.movement;

import com.warehouse.entity.MovementType;

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
        Long id,
        MovementType type,
        int quantity,
        String performedBy,
        LocalDateTime createdAt,
        Long warehouseId,
        String warehouseName,
        UUID transferId
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
