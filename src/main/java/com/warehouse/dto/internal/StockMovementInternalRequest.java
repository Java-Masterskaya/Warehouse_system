package com.warehouse.dto.internal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Внутренний DTO для сервисного слоя.
 * Контроллер получает пользователя безопасным путем
 * через @AuthenticationPrincipal,
 * извлекает userId из токена и добавляет во внутренний DTO.
 * DTO содержит все данные, необходимые для выполнения бизнес-логики.
 * @param itemId
 * @param quantity
 * @param userId
 */
public record StockMovementInternalRequest(
        @NotNull(message = "ID товара обязателен")
        Long itemId,

        @NotNull(message = "Количество обязательно")
        @Min(value = 1, message = "Количество должно быть больше нуля")
        Integer quantity,

        // Не приходит от клиента, а берется контроллером ИЗ Security Context,
        // что  предотвращает подмену пользователя
        @NotNull(message = "ID пользователя обязателен")
        Long userId
) {
}