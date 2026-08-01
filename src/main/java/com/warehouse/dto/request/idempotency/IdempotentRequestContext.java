package com.warehouse.dto.request.idempotency;

import com.warehouse.dto.UserContext;

/**
 * DTO для группировки параметров идемпотентного запроса.
 * Используется для передачи данных в сервис идемпотентности.
 *
 * @param idempotencyKey идемпотентный ключ из заголовка Idempotency-Key
 * @param endpoint       эндпоинт, на который был отправлен запрос
 * @param userContext    контекст пользователя, выполняющего операцию
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
