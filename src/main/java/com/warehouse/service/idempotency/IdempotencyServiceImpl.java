package com.warehouse.service.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.idempotency.IdempotentRequestContext;
import com.warehouse.dto.request.movement.ReceiveStockRequest;
import com.warehouse.dto.request.movement.WriteOffStockRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.IdempotencyKey;
import com.warehouse.entity.User;
import com.warehouse.exception.IdempotencyConflictException;
import com.warehouse.exception.IdempotencyExecutionException;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Supplier;

@Service
@Slf4j
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final int HTTP_ERROR_THRESHOLD = 400;

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.idempotency.ttl-hours:24}")
    private long ttlHours;

    @Value("${app.idempotency.required:false}")
    private boolean idempotencyRequired;

    @Value("${app.idempotency.enforce-after:}")
    private String enforceAfterDate;

    @Override
    @Transactional
    @Retryable(
            retryFor = IdempotencyKeyDuplicateException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 100)
    )
    public StockMovementResponse processIdempotentRequest(
            IdempotentRequestContext context,
            Object requestBody,
            Supplier<StockMovementResponse> operation
    ) {
        String key = context.idempotencyKey();
        String endpoint = context.endpoint();
        UserContext ctx = context.userContext();

        if (!context.hasKey()) {
            return handleRequestWithoutKey(endpoint, ctx, operation);
        }

        String keyHash = TokenHashUtil.hashToken(key);
        log.debug("Processing idempotent request, endpoint: {}", endpoint);

        Optional<IdempotencyKey> existingKey = idempotencyKeyRepository
                .findByKeyHashAndUserIdAndEndpoint(keyHash, ctx.userId(), endpoint, LocalDateTime.now());

        if (existingKey.isPresent()) {
            return handleExistingKey(existingKey.get(), key, endpoint, requestBody);
        }

        return handleNewKey(keyHash, ctx, endpoint, requestBody, operation);
    }

    private StockMovementResponse handleRequestWithoutKey(
            String endpoint,
            UserContext ctx,
            Supplier<StockMovementResponse> operation
    ) {
        if (shouldEnforceIdempotency()) {
            throw IdempotencyKeyRequiredException.forEndpoint(endpoint);
        }
        log.warn("Request without Idempotency-Key received during grace period. "
                        + "Endpoint: {}, User: {}. This will become required after {}",
                endpoint, ctx.username(), enforceAfterDate);
        return operation.get();
    }

    private StockMovementResponse handleExistingKey(
            IdempotencyKey idempotencyKey,
            String key,
            String endpoint,
            Object requestBody
    ) {
        validateRequestBodyHash(idempotencyKey, requestBody, key, endpoint);
        validateKeyStatus(idempotencyKey, endpoint);
        return deserializeCachedResponse(idempotencyKey, endpoint);
    }

    private void validateRequestBodyHash(
            IdempotencyKey idempotencyKey,
            Object requestBody,
            String key,
            String endpoint
    ) {
        String currentBodyHash = hashRequestBody(requestBody);
        String storedBodyHash = idempotencyKey.getRequestBodyHash();

        if (!storedBodyHash.equals(currentBodyHash)) {
            log.warn("Idempotency conflict: same key but different body for user {}, endpoint {}",
                    idempotencyKey.getUser().getId(), endpoint);
            throw IdempotencyConflictException.of(endpoint);
        }
    }

    private void validateKeyStatus(IdempotencyKey idempotencyKey, String endpoint) {
        String responseBody = idempotencyKey.getResponseBody();
        Integer statusCode = idempotencyKey.getStatusCode();

        if (responseBody == null || responseBody.isEmpty()) {
            throw new IdempotencyExecutionException(
                    "Request with this idempotency key is currently being processed"
            );
        }

        if (statusCode != null && statusCode >= HTTP_ERROR_THRESHOLD) {
            log.warn("Idempotency key failed previously: endpoint={}, statusCode={}", endpoint, statusCode);
            throw new IdempotencyExecutionException("Previous request with this idempotency key failed");
        }
    }

    private StockMovementResponse deserializeCachedResponse(IdempotencyKey idempotencyKey, String endpoint) {
        try {
            log.info("Returning cached response for idempotent request, endpoint={}", endpoint);
            return objectMapper.readValue(idempotencyKey.getResponseBody(), StockMovementResponse.class);
        } catch (JsonProcessingException e) {
            log.error("CRITICAL: Failed to deserialize cached response for endpoint={}. "
                    + "This indicates data corruption or incompatible DTO changes. "
                    + "Error: {}", endpoint, e.getMessage());
            throw new IdempotencyStorageException(
                    "Failed to retrieve cached response for idempotency key", e
            );
        }
    }

    private StockMovementResponse handleNewKey(
            String keyHash,
            UserContext ctx,
            String endpoint,
            Object requestBody,
            Supplier<StockMovementResponse> operation
    ) {
        // Удаляем просроченный ключ, если он есть
        idempotencyKeyRepository.deleteExpiredKey(keyHash, ctx.userId(), endpoint, LocalDateTime.now());

        String bodyHash = hashRequestBody(requestBody);
        saveIdempotencyKeyPlaceholder(keyHash, ctx, endpoint, bodyHash);

        try {
            StockMovementResponse response = operation.get();
            updateIdempotencyKeyWithResult(keyHash, ctx, endpoint, response);
            log.info("Idempotency key saved for endpoint={} and userId={}", endpoint, ctx.userId());
            return response;
        } catch (IdempotencyStorageException e) {
            markIdempotencyKeyAsFailed(keyHash, ctx, endpoint, e);
            throw e;
        } catch (Exception e) {
            markIdempotencyKeyAsFailed(keyHash, ctx, endpoint, e);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }

    private void saveIdempotencyKeyPlaceholder(String keyHash, UserContext ctx, String endpoint, String bodyHash) {
        User user = userRepository.getReferenceById(ctx.userId());

        IdempotencyKey idempotencyKey = IdempotencyKey.builder()
                .keyHash(keyHash)
                .user(user)
                .endpoint(endpoint)
                .requestBodyHash(bodyHash)
                .responseBody("")
                .statusCode(HttpStatus.PROCESSING.value())
                .expiresAt(LocalDateTime.now().plusHours(ttlHours))
                .build();

        try {
            idempotencyKeyRepository.save(idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            throw new IdempotencyKeyDuplicateException("Duplicate idempotency key", e);
        }
    }

    private void updateIdempotencyKeyWithResult(
            String keyHash,
            UserContext ctx,
            String endpoint,
            StockMovementResponse response
    ) {
        try {
            String responseJson = objectMapper.writeValueAsString(response);
            int updated = idempotencyKeyRepository.updateResponseAndStatus(
                    keyHash, ctx.userId(), endpoint,
                    responseJson, HttpStatus.OK.value(),
                    LocalDateTime.now().plusHours(ttlHours)
            );

            if (updated == 0) {
                log.warn("Failed to update idempotency key: keyHash={}, userId={}, endpoint={}",
                        keyHash, ctx.userId(), endpoint);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response for idempotency key: {}", e.getMessage());
            throw new IdempotencyStorageException("Failed to store idempotency key", e);
        }
    }

    private void markIdempotencyKeyAsFailed(String keyHash, UserContext ctx, String endpoint, Exception e) {
        String errorMessage = e.getMessage();
        String escapedMessage;

        if (errorMessage != null) {
            escapedMessage = errorMessage.replace("\"", "\\\"").replace("\n", " ");
        } else {
            escapedMessage = "Unknown error";
        }

        String errorJson = String.format(
                "{\"error\":\"%s\",\"message\":\"%s\"}",
                e.getClass().getSimpleName(),
                escapedMessage
        );

        idempotencyKeyRepository.updateResponseAndStatus(
                keyHash, ctx.userId(), endpoint,
                errorJson,
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                LocalDateTime.now().plusHours(ttlHours)
        );
    }

    private String hashRequestBody(Object request) {
        if (request instanceof ReceiveStockRequest receiveRequest) {
            return TokenHashUtil.hashToken(
                    receiveRequest.itemId() + ":" + receiveRequest.quantity()
            );
        } else if (request instanceof WriteOffStockRequest writeOffRequest) {
            return TokenHashUtil.hashToken(
                    writeOffRequest.itemId() + ":" + writeOffRequest.quantity()
            );
        }
        throw new IllegalArgumentException("Unsupported request body type: "
                + request.getClass().getName());
    }

    private boolean shouldEnforceIdempotency() {
        if (enforceAfterDate != null && !enforceAfterDate.isEmpty()) {
            try {
                LocalDate enforceDate = LocalDate.parse(enforceAfterDate);
                LocalDate now = LocalDate.now();
                return now.isAfter(enforceDate) || now.isEqual(enforceDate);
            } catch (DateTimeParseException e) {
                log.warn("Invalid enforceAfterDate format: {}. Expected yyyy-MM-dd", enforceAfterDate);
                return idempotencyRequired;
            }
        }
        return idempotencyRequired;
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