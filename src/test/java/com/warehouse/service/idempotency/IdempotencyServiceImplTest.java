package com.warehouse.service.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.idempotency.IdempotentRequestContext;
import com.warehouse.dto.request.movement.ChangeQuantityMovementRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.IdempotencyKey;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.User;
import com.warehouse.exception.IdempotencyConflictException;
import com.warehouse.exception.IdempotencyKeyRequiredException;
import com.warehouse.exception.IdempotencyStorageException;
import com.warehouse.repository.IdempotencyKeyRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.util.TokenHashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceImplTest {

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private IdempotencyServiceImpl idempotencyService;

    private static final Long USER_ID = 1L;
    private static final String USERNAME = "admin";
    private static final String ENDPOINT = "/api/movements/receive";
    private static final String IDEMPOTENCY_KEY = UUID.randomUUID().toString();
    private static final String KEY_HASH = TokenHashUtil.hashToken(IDEMPOTENCY_KEY);

    private UserContext userContext;
    private ChangeQuantityMovementRequest requestBody;
    private IdempotentRequestContext context;
    private StockMovementResponse response;
    private User user;

    @BeforeEach
    void setUp() {
        userContext = new UserContext(USER_ID, USERNAME);
        requestBody = new ChangeQuantityMovementRequest(1L, 5);
        context = new IdempotentRequestContext(IDEMPOTENCY_KEY, ENDPOINT, userContext);
        response = new StockMovementResponse(
                1L, 100L, MovementType.RECEIVE, 5, 10,
                LocalDateTime.now(), false
        );
        user = User.builder()
                .id(USER_ID)
                .username(USERNAME)
                .build();

        ReflectionTestUtils.setField(idempotencyService, "ttlHours", 24L);
        ReflectionTestUtils.setField(idempotencyService, "idempotencyRequired", true);
    }

    @Nested
    @DisplayName("1. Сценарии без идемпотентного ключа")
    class WithoutKeyTests {

        @Test
        @DisplayName("Должен выбросить исключение, если ключ обязателен и не передан")
        void shouldThrowExceptionWhenKeyRequiredAndNotProvided() {
            // given
            IdempotentRequestContext contextWithoutKey =
                    new IdempotentRequestContext(null, ENDPOINT, userContext);
            Supplier<StockMovementResponse> operation = () -> response;

            // when & then
            assertThatThrownBy(() ->
                    idempotencyService.processIdempotentRequest(contextWithoutKey, requestBody, operation)
            )
                    .isInstanceOf(IdempotencyKeyRequiredException.class)
                    .hasMessageContaining("Idempotency-Key header is required for "
                            + ENDPOINT + " endpoint");
        }

        @Test
        @DisplayName("Должен выполнить операцию, если ключ НЕ обязателен и не передан")
        void shouldExecuteOperationWhenKeyNotRequiredAndNotProvided() {
            // given
            ReflectionTestUtils.setField(idempotencyService, "idempotencyRequired", false);
            IdempotentRequestContext contextWithoutKey =
                    new IdempotentRequestContext(null, ENDPOINT, userContext);
            Supplier<StockMovementResponse> operation = () -> response;

            // when
            StockMovementResponse result = idempotencyService.processIdempotentRequest(
                    contextWithoutKey, requestBody, operation
            );

            // then
            assertThat(result).isEqualTo(response);
            verify(idempotencyKeyRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("2. Сценарии с новым идемпотентным ключом")
    class NewKeyTests {

        @Test
        @DisplayName("Первый запрос с новым ключом - должен создать движение и сохранить ключ")
        void shouldCreateMovementAndSaveKeyForNewRequest() throws Exception {
            // given
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    KEY_HASH, USER_ID, ENDPOINT
            )).thenReturn(Optional.empty());

            when(userRepository.getReferenceById(USER_ID)).thenReturn(user);

            String responseJson = """
                    {"itemId":1,"movementId":100,"type":"RECEIVE","quantity":5,"stockAfter":10,"lowStockAlert":false}
                    """;
            when(objectMapper.writeValueAsString(response)).thenReturn(responseJson);

            Supplier<StockMovementResponse> operation = () -> response;

            // when
            StockMovementResponse result = idempotencyService.processIdempotentRequest(
                    context, requestBody, operation
            );

            // then
            assertThat(result).isEqualTo(response);

            ArgumentCaptor<IdempotencyKey> keyCaptor = ArgumentCaptor.forClass(IdempotencyKey.class);
            verify(idempotencyKeyRepository).save(keyCaptor.capture());

            IdempotencyKey savedKey = keyCaptor.getValue();
            assertThat(savedKey.getKeyHash()).isEqualTo(KEY_HASH);
            assertThat(savedKey.getUser().getId()).isEqualTo(USER_ID);
            assertThat(savedKey.getEndpoint()).isEqualTo(ENDPOINT);
            assertThat(savedKey.getResponseBody()).isEqualTo(responseJson);
            assertThat(savedKey.getRequestBodyHash()).isNotNull();
            assertThat(savedKey.getStatusCode()).isEqualTo(200);
            assertThat(savedKey.getExpiresAt()).isAfter(LocalDateTime.now());
        }

        @Test
        @DisplayName("Ошибка сериализации ответа - должен выбросить исключение и не сохранять ключ")
        void shouldThrowExceptionWhenSerializationFails() throws Exception {
            // given
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    KEY_HASH, USER_ID, ENDPOINT
            )).thenReturn(Optional.empty());

            when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
            when(objectMapper.writeValueAsString(response))
                    .thenThrow(new JsonProcessingException("Serialization error") {
                    });

            Supplier<StockMovementResponse> operation = () -> response;

            // when & then
            assertThatThrownBy(() ->
                    idempotencyService.processIdempotentRequest(context, requestBody, operation)
            )
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to save idempotency key");

            verify(idempotencyKeyRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("3. Сценарии с существующим идемпотентным ключом")
    class ExistingKeyTests {

        private IdempotencyKey existingKey;
        private String bodyHash;

        @BeforeEach
        void setUp() {
            bodyHash = TokenHashUtil.hashToken("1:5");
            existingKey = IdempotencyKey.builder()
                    .keyHash(KEY_HASH)
                    .user(user)
                    .endpoint(ENDPOINT)
                    .requestBodyHash(bodyHash)
                    .responseBody("{\"itemId\":1,\"movementId\":100,\"type\":\"RECEIVE\","
                            + "\"quantity\":5,\"stockAfter\":10,\"lowStockAlert\":false}")
                    .statusCode(200)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
        }

        @Test
        @DisplayName("Повторный запрос с тем же ключом и телом - должен вернуть кешированный ответ")
        void shouldReturnCachedResponseForDuplicateRequest() throws Exception {
            // given
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    KEY_HASH, USER_ID, ENDPOINT
            )).thenReturn(Optional.of(existingKey));

            when(objectMapper.readValue(existingKey.getResponseBody(), StockMovementResponse.class))
                    .thenReturn(response);

            Supplier<StockMovementResponse> operation = () -> {
                throw new RuntimeException("Operation should not be called");
            };

            // when
            StockMovementResponse result = idempotencyService.processIdempotentRequest(
                    context, requestBody, operation
            );

            // then
            assertThat(result).isEqualTo(response);
            verify(idempotencyKeyRepository, never()).save(any());
        }

        @Test
        @DisplayName("Тот же ключ, но другое тело - должен выбросить IdempotencyConflictException")
        void shouldThrowConflictExceptionWhenSameKeyDifferentBody() {
            // given
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    KEY_HASH, USER_ID, ENDPOINT
            )).thenReturn(Optional.of(existingKey));

            ChangeQuantityMovementRequest differentBody = new ChangeQuantityMovementRequest(1L, 10);
            Supplier<StockMovementResponse> operation = () -> response;

            // when & then
            assertThatThrownBy(() ->
                    idempotencyService.processIdempotentRequest(context, differentBody, operation)
            )
                    .isInstanceOf(IdempotencyConflictException.class)
                    .hasMessageContaining("is already used with different request body for endpoint");
        }

        @Test
        @DisplayName("Ошибка десериализации кеша - должен выбросить IdempotencyStorageException")
        void shouldThrowStorageExceptionWhenDeserializationFails() throws Exception {
            // given
            IdempotencyKey invalidKey = IdempotencyKey.builder()
                    .keyHash(KEY_HASH)
                    .user(user)
                    .endpoint(ENDPOINT)
                    .requestBodyHash(bodyHash)
                    .responseBody("invalid json")
                    .statusCode(200)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();

            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    KEY_HASH, USER_ID, ENDPOINT
            )).thenReturn(Optional.of(invalidKey));

            when(objectMapper.readValue("invalid json", StockMovementResponse.class))
                    .thenThrow(new JsonProcessingException("Invalid JSON") {
                    });

            Supplier<StockMovementResponse> operation = () -> response;

            // when & then
            assertThatThrownBy(() ->
                    idempotencyService.processIdempotentRequest(context, requestBody, operation)
            )
                    .isInstanceOf(IdempotencyStorageException.class)
                    .hasMessageContaining("Failed to retrieve cached response");
        }
    }

    @Nested
    @DisplayName("4. Разные ключи")
    class DifferentKeysTests {

        @Test
        @DisplayName("Разные ключи должны создавать разные движения")
        void shouldCreateDifferentMovementsForDifferentKeys() throws Exception {
            // given
            String secondKey = UUID.randomUUID().toString();
            String secondKeyHash = TokenHashUtil.hashToken(secondKey);
            IdempotentRequestContext secondContext =
                    new IdempotentRequestContext(secondKey, ENDPOINT, userContext);

            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    KEY_HASH, USER_ID, ENDPOINT
            )).thenReturn(Optional.empty());

            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    secondKeyHash, USER_ID, ENDPOINT
            )).thenReturn(Optional.empty());

            when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
            when(objectMapper.writeValueAsString(any(StockMovementResponse.class)))
                    .thenReturn("{}");

            Supplier<StockMovementResponse> operation = () -> response;

            // when
            StockMovementResponse result1 = idempotencyService.processIdempotentRequest(
                    context, requestBody, operation
            );
            StockMovementResponse result2 = idempotencyService.processIdempotentRequest(
                    secondContext, requestBody, operation
            );

            // then
            assertThat(result1).isEqualTo(response);
            assertThat(result2).isEqualTo(response);
            verify(idempotencyKeyRepository, times(2)).save(any());
        }
    }

    @Nested
    @DisplayName("5. Параллельные запросы")
    class ConcurrentTests {

        @Test
        @DisplayName("Параллельные запросы с одинаковым ключом - не должны создавать дубли")
        void shouldNotCreateDuplicatesForConcurrentRequests() throws Exception {
            // given
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger operationCallCount = new AtomicInteger(0);

            // Мокаем: первый раз ключа нет, потом есть
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    KEY_HASH, USER_ID, ENDPOINT
            )).thenAnswer(invocation -> {
                if (operationCallCount.get() == 0) {
                    return Optional.empty();
                } else {
                    return Optional.of(IdempotencyKey.builder()
                            .keyHash(KEY_HASH)
                            .user(user)
                            .endpoint(ENDPOINT)
                            .requestBodyHash(TokenHashUtil.hashToken("1:5"))
                            .responseBody("{}")
                            .statusCode(200)
                            .expiresAt(LocalDateTime.now().plusHours(24))
                            .build());
                }
            });

            when(userRepository.getReferenceById(USER_ID)).thenReturn(user);

            // Имитируем DataIntegrityViolationException при конкурентной вставке
            when(idempotencyKeyRepository.save(any(IdempotencyKey.class)))
                    .thenAnswer(invocation -> {
                        if (operationCallCount.incrementAndGet() == 1) {
                            // Первый сохраняет успешно
                            return invocation.getArgument(0);
                        } else {
                            // Второй получает ошибку (симулируем дубликат)
                            throw new DataIntegrityViolationException("Duplicate key");
                        }
                    });

            when(objectMapper.writeValueAsString(any(StockMovementResponse.class)))
                    .thenReturn("{}");

            Supplier<StockMovementResponse> operation = () -> {
                operationCallCount.incrementAndGet();
                return response;
            };

            // when
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        latch.await();
                        idempotencyService.processIdempotentRequest(
                                context, requestBody, operation
                        );
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // Ожидаем, что некоторые потоки могут упасть с ошибкой
                    }
                });
            }

            latch.countDown();
            executor.shutdown();
            boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);

            // then
            assertThat(finished).isTrue();
            // Операция должна быть вызвана только 1-2 раза (успешно + возможно retry)
            assertThat(operationCallCount.get()).isLessThanOrEqualTo(2);
            // Все запросы должны завершиться успешно (кроме тех, что упали)
            assertThat(successCount.get()).isBetween(1, threadCount);
        }
    }

    @Nested
    @DisplayName("6. Очистка просроченных ключей")
    class CleanupTests {

        @Test
        @DisplayName("Должен удалить просроченные ключи")
        void shouldDeleteExpiredKeys() {
            // given
            when(idempotencyKeyRepository.deleteExpiredKeys(any(LocalDateTime.class)))
                    .thenReturn(5);

            // when
            int deleted = idempotencyService.cleanExpiredKeys();

            // then
            assertThat(deleted).isEqualTo(5);
            verify(idempotencyKeyRepository).deleteExpiredKeys(any(LocalDateTime.class));
        }

        @Test
        @DisplayName("Должен корректно обработать отсутствие просроченных ключей")
        void shouldHandleNoExpiredKeys() {
            // given
            when(idempotencyKeyRepository.deleteExpiredKeys(any(LocalDateTime.class)))
                    .thenReturn(0);

            // when
            int deleted = idempotencyService.cleanExpiredKeys();

            // then
            assertThat(deleted).isEqualTo(0);
            verify(idempotencyKeyRepository).deleteExpiredKeys(any(LocalDateTime.class));
        }
    }
}