package com.warehouse.dto.response.movement;

import com.warehouse.entity.MovementType;

import java.time.LocalDateTime;

/**
 * DTO ответа, содержащий информацию о движении товара.
 *
 * @param id          идентификатор записи о движении
 * @param type        тип движения товара
 * @param quantity    количество единиц товара
 * @param performedBy имя пользователя, выполнившего операцию
 * @param createdAt   дата и время выполнения операции
 */
public record StockMovementHistoryResponse(
        Long id,
        MovementType type,
        int quantity,
        String performedBy,
        LocalDateTime createdAt) {
}
