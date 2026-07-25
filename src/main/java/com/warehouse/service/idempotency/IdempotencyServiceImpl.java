package com.warehouse.service.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.idempotency.IdempotentRequestContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.IdempotencyKey;
import com.warehouse.entity.User;
import com.warehouse.exception.IdempotencyConflictException;
import com.warehouse.exception.IdempotencyKeyDuplicateException;
import com.warehouse.exception.IdempotencyKeyRequiredException;
import com.warehouse.exception.IdempotencyStorageException;
import com.warehouse.repository.IdempotencyKeyRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.util.TokenHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

@Service
@Slf4j
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.idempotency.ttl-hours:24}")
    private long ttlHours;

    @Value("${app.idempotency.required:true}")
    private boolean idempotencyRequired;

    @Override
    @Transactional
    @Retryable(
            retryFor = IdempotencyKeyDuplicateException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100)
    )
    public StockMovementResponse processIdempotentRequest(
            IdempotentRequestContext context,
            ChangeQuantityMovementRequest requestBody,
            Supplier<StockMovementResponse> operation
    ) {
        String key = context.idempotencyKey();
        String endpoint = context.endpoint();
        UserContext ctx = context.userContext();

        // Если ключ не предоставлен - либо ошибка, либо выполняем обычную обработку в зависимости от настройки
        if (!context.hasKey()) {
            if (idempotencyRequired) {
                throw IdempotencyKeyRequiredException.forEndpoint(endpoint);
            }
            log.debug("Executing non-idempotent request for endpoint: {}", endpoint);
            return operation.get();
        }

        String keyHash = TokenHashUtil.hashToken(key);
        log.debug("Processing idempotent request, endpoint: {}", endpoint);

        // Проверяем существующий ключ
        Optional<IdempotencyKey> existingKey = idempotencyKeyRepository
                .findByKeyHashAndUserIdAndEndpoint(keyHash, ctx.userId(), endpoint, LocalDateTime.now());

        if (existingKey.isPresent()) {
            IdempotencyKey idempotencyKey = existingKey.get();

            // Проверяем, что тело запроса не изменилось
            String currentBodyHash = hashRequestBody(requestBody);
            String storedBodyHash = idempotencyKey.getRequestBodyHash();

            if (!storedBodyHash.equals(currentBodyHash)) {
                log.warn("Idempotency conflict: same key but different body for user {}, endpoint {}",
                        ctx.userId(), endpoint);
                throw IdempotencyConflictException.of(key, endpoint);
            }

            // Возвращаем закешированный ответ (полный JSON из response_body)
            try {
                log.info("Returning cached response for idempotent request, endpoint={}", endpoint);
                return objectMapper.readValue(idempotencyKey.getResponseBody(), StockMovementResponse.class);
            } catch (JsonProcessingException e) {
                // Критическая ошибка - не можем десериализовать сохраненный ответ
                // Это указывает на повреждение данных в БД или изменение структуры DTO
                log.error("CRITICAL: Failed to deserialize cached response for endpoint={}. "
                        + "This indicates data corruption or incompatible DTO changes. "
                        + "Error: {}", endpoint, e.getMessage());
                throw new IdempotencyStorageException(
                        "Failed to retrieve cached response for idempotency key", e
                );
            }
        }

        // Новый ключ - выполняем операцию
        StockMovementResponse response = operation.get();

        // Сохраняем ключ с результатом
        saveIdempotencyKey(keyHash, ctx, endpoint, response, requestBody);
        log.info("Idempotency key saved for endpoint={} and userId={}", endpoint, ctx.userId());

        return response;
    }

    private void saveIdempotencyKey(
            String keyHash,
            UserContext ctx,
            String endpoint,
            StockMovementResponse response,
            ChangeQuantityMovementRequest requestBody
    ) {
        try {
            User user = userRepository.getReferenceById(ctx.userId());
            String responseJson = objectMapper.writeValueAsString(response);
            String bodyHash = hashRequestBody(requestBody);

            IdempotencyKey idempotencyKey = IdempotencyKey.builder()
                    .keyHash(keyHash)
                    .user(user)
                    .endpoint(endpoint)
                    .requestBodyHash(bodyHash)
                    .responseBody(responseJson)
                    .statusCode(HttpStatus.OK.value())
                    .expiresAt(LocalDateTime.now().plusHours(ttlHours))
                    .build();

            idempotencyKeyRepository.save(idempotencyKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response for idempotency key: {}", e.getMessage());
            throw new RuntimeException("Failed to save idempotency key", e);
        } catch (DataIntegrityViolationException e) {
        // Конфликт уникальности (key_hash, user_id, endpoint)
        throw new IdempotencyKeyDuplicateException("Duplicate idempotency key", e);
    }

    }

    private String hashRequestBody(ChangeQuantityMovementRequest request) {
        // Создаем детерминированную строку для сравнения тел запросов
        return TokenHashUtil.hashToken(
                request.itemId() + ":" + request.quantity()
        );
    }

    @Override
    @Transactional
    @Scheduled(cron = "${app.idempotency.cleanup-cron:0 0 * * * *}")
    public int cleanExpiredKeys() {
        int deleted = idempotencyKeyRepository.deleteExpiredKeys(LocalDateTime.now());
        if (deleted > 0) {
            log.info("Cleaned up {} expired idempotency keys", deleted);
        }
        return deleted;
    }
}