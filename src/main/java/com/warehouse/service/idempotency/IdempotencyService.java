package com.warehouse.service.idempotency;

import com.warehouse.dto.UserContext;

import com.warehouse.dto.request.idempotency.IdempotentRequestContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;

import java.util.function.Supplier;

/**
 * Сервис для обеспечения идемпотентности операций с товарами.
 * Предоставляет методы для обработки запросов с идемпотентными ключами,
 * кеширования результатов и очистки устаревших ключей.
 */
public interface IdempotencyService {

    /**
     * Обрабатывает идемпотентный запрос.
     *
     * <p>Логика работы:
     * <ul>
     *   <li>Если ключ не предоставлен - либо выбрасывает исключение (если ключи обязательны),
     *       либо выполняет операцию обычным способом</li>
     *   <li>Если ключ уже существует - проверяет соответствие тела запроса и возвращает
     *       закешированный ответ, не выполняя операцию повторно</li>
     *   <li>Если ключ новый - выполняет операцию и сохраняет результат в кеше</li>
     * </ul>
     *
     * @param context контекст идемпотентного запроса (ключ, эндпоинт, пользователь)
     * @param requestBody тело запроса для проверки конфликтов при повторных вызовах
     * @param operation функция, выполняющая бизнес-логику (приход/списание)
     * @return результат выполнения операции или закешированный ответ
     * @throws IdempotencyKeyRequiredException если ключ обязателен, но не предоставлен
     * @throws IdempotencyConflictException если тот же ключ используется с другим телом запроса
     * @throws RuntimeException если не удалось сериализовать/десериализовать ответ
     */
    StockMovementResponse processIdempotentRequest(
            IdempotentRequestContext context,
            ChangeQuantityMovementRequest requestBody,
            Supplier<StockMovementResponse> operation
    );

    /**
     * Очищает просроченные идемпотентные ключи.
     *
     * <p>Метод удаляет все записи, у которых expires_at меньше текущего времени.
     * Рекомендуется запускать по расписанию для предотвращения разрастания таблицы.
     *
     * @return количество удаленных записей
     */
    int cleanExpiredKeys();
}