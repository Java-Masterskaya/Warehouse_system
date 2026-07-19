package com.warehouse.dto.request.idempotency;

import com.warehouse.dto.UserContext;

/**
 * DTO для группировки параметров идемпотентного запроса.
 * Используется для передачи данных в сервис идемпотентности.
 */
public record IdempotentRequestContext(
        String idempotencyKey,
        String endpoint,
        UserContext userContext
) {

    /**
     * Проверяет, присутствует ли идемпотентный ключ.
     *
     * @return true если ключ не null и не пустой
     */
    public boolean hasKey() {
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }
}
