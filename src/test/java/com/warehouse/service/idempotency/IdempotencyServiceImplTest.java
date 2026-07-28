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
import com.warehouse.exception.IdempotencyExecutionException;
import com.warehouse.exception.IdempotencyKeyDuplicateException;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT), any(LocalDateTime.class)
            )).thenReturn(Optional.empty());

            when(userRepository.getReferenceById(USER_ID)).thenReturn(user);

            String responseJson = "{\"itemId\":1,\"movementId\":100,\"type\":\"RECEIVE\","
                    + "\"quantity\":5,\"stockAfter\":10,\"lowStockAlert\":false}";
            when(objectMapper.writeValueAsString(response)).thenReturn(responseJson);

            Supplier<StockMovementResponse> operation = () -> response;

            // when
            StockMovementResponse result = idempotencyService.processIdempotentRequest(
                    context, requestBody, operation
            );

            // then
            assertThat(result).isEqualTo(response);

            // Проверяем сохранение placeholder
            ArgumentCaptor<IdempotencyKey> placeholderCaptor = ArgumentCaptor.forClass(IdempotencyKey.class);
            verify(idempotencyKeyRepository, times(1)).save(placeholderCaptor.capture());
            IdempotencyKey savedPlaceholder = placeholderCaptor.getValue();
            assertThat(savedPlaceholder.getResponseBody()).isEmpty();  // ← пустой маркер
            assertThat(savedPlaceholder.getStatusCode()).isEqualTo(102);  // PROCESSING

            // Проверяем обновление результата
            verify(idempotencyKeyRepository, times(1)).updateResponseAndStatus(
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT),
                    eq(responseJson), eq(200), any(LocalDateTime.class)
            );
        }

        @Test
        @DisplayName("Ошибка сериализации ответа - должен выбросить IdempotencyStorageException")
        void shouldThrowExceptionWhenSerializationFails() throws Exception {
            // given
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT), any(LocalDateTime.class)
            )).thenReturn(Optional.empty());

            when(userRepository.getReferenceById(USER_ID)).thenReturn(user);

            // Мокаем падение сериализации для response
            when(objectMapper.writeValueAsString(any(StockMovementResponse.class)))
                    .thenThrow(new JsonProcessingException("Serialization error") {
                    });

            Supplier<StockMovementResponse> operation = () -> response;

            // when & then
            assertThatThrownBy(() ->
                    idempotencyService.processIdempotentRequest(context, requestBody, operation)
            )
                    .isInstanceOf(IdempotencyStorageException.class)
                    .hasMessageContaining("Failed to store idempotency key");

            // Проверяем, что placeholder был сохранен
            verify(idempotencyKeyRepository, times(1)).save(any(IdempotencyKey.class));

            // Проверяем, что update был вызван с любым строковым значением (не null)
            verify(idempotencyKeyRepository, times(1)).updateResponseAndStatus(
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT),
                    anyString(), // теперь точно не null
                    eq(422),
                    any(LocalDateTime.class)
            );
        }

        @Test
        @DisplayName("Конфликт уникальности при сохранении - должен выбросить IdempotencyKeyDuplicateException")
        void shouldThrowDuplicateExceptionWhenUniqueConstraintViolated() throws Exception {
            // given
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT), any(LocalDateTime.class)
            )).thenReturn(Optional.empty());

            when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
            when(idempotencyKeyRepository.save(any(IdempotencyKey.class)))
                    .thenThrow(new DataIntegrityViolationException("Duplicate key"));

            Supplier<StockMovementResponse> operation = () -> response;

            // when & then
            assertThatThrownBy(() ->
                    idempotencyService.processIdempotentRequest(context, requestBody, operation)
            )
                    .isInstanceOf(IdempotencyKeyDuplicateException.class)
                    .hasMessageContaining("Duplicate idempotency key");
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
            String responseJson = "{\"itemId\":1,\"movementId\":100,\"type\":\"RECEIVE\","
                    + "\"quantity\":5,\"stockAfter\":10,\"lowStockAlert\":false}";
            String requestBodyHash = TokenHashUtil.hashToken("1:5");

            IdempotencyKey cachedKey = IdempotencyKey.builder()
                    .keyHash(KEY_HASH)
                    .user(user)
                    .endpoint(ENDPOINT)
                    .requestBodyHash(requestBodyHash)
                    .responseBody(responseJson)  // ← (COMPLETED)
                    .statusCode(200)             // ← 200 OK
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();

            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT), any(LocalDateTime.class)
            )).thenReturn(Optional.of(cachedKey));

            when(objectMapper.readValue(responseJson, StockMovementResponse.class))
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
        @DisplayName("Повторный запрос во время обработки - должен выбросить IdempotencyExecutionException")
        void shouldThrowExceptionWhenKeyIsProcessing() {
            // given
            String requestBodyHash = TokenHashUtil.hashToken("1:5");
            IdempotencyKey processingKey = IdempotencyKey.builder()
                    .keyHash(KEY_HASH)
                    .user(user)
                    .endpoint(ENDPOINT)
                    .requestBodyHash(requestBodyHash)
                    .responseBody("")  // ← ПУСТОЙ! (PROCESSING)
                    .statusCode(102)   // PROCESSING
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();

            // Используем any() для LocalDateTime
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT), any(LocalDateTime.class)
            )).thenReturn(Optional.of(processingKey));

            Supplier<StockMovementResponse> operation = () -> response;

            // when & then
            assertThatThrownBy(() ->
                    idempotencyService.processIdempotentRequest(context, requestBody, operation)
            )
                    .isInstanceOf(IdempotencyExecutionException.class)
                    .hasMessageContaining("currently being processed");
        }

        @Test
        @DisplayName("Повторный запрос после ошибки - должен выбросить IdempotencyExecutionException")
        void shouldThrowExceptionWhenKeyFailed() {
            // given
            String requestBodyHash = TokenHashUtil.hashToken("1:5");
            String errorJson = "{\"error\":\"InsufficientStockException\",\"message\":\"Not enough stock\"}";
            IdempotencyKey failedKey = IdempotencyKey.builder()
                    .keyHash(KEY_HASH)
                    .user(user)
                    .endpoint(ENDPOINT)
                    .requestBodyHash(requestBodyHash)
                    .responseBody(errorJson)
                    .statusCode(422)    // UNPROCESSABLE_ENTITY
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();

            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT), any(LocalDateTime.class)
            )).thenReturn(Optional.of(failedKey));

            Supplier<StockMovementResponse> operation = () -> response;

            // when & then
            assertThatThrownBy(() ->
                    idempotencyService.processIdempotentRequest(context, requestBody, operation)
            )
                    .isInstanceOf(IdempotencyExecutionException.class)
                    .hasMessageContaining("Previous request with this idempotency key failed");
        }

        @Test
        @DisplayName("Тот же ключ, но другое тело - должен выбросить IdempotencyConflictException")
        void shouldThrowConflictExceptionWhenSameKeyDifferentBody() {
            // given
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT), any(LocalDateTime.class)
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
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT), any(LocalDateTime.class)
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

        @Test
        @DisplayName("Просроченный ключ - должен считаться отсутствующим и выполнить операцию")
        void shouldTreatExpiredKeyAsNotFound() throws Exception {
            // given
            IdempotencyKey expiredKey = IdempotencyKey.builder()
                    .keyHash(KEY_HASH)
                    .user(user)
                    .endpoint(ENDPOINT)
                    .requestBodyHash(bodyHash)
                    .responseBody("{}")
                    .statusCode(200)
                    .expiresAt(LocalDateTime.now().minusHours(1))  // Просрочен
                    .build();

            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT), any(LocalDateTime.class)
            )).thenReturn(Optional.empty());

            when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
            when(objectMapper.writeValueAsString(response)).thenReturn("{}");

            Supplier<StockMovementResponse> operation = () -> response;

            // when
            StockMovementResponse result = idempotencyService.processIdempotentRequest(
                    context, requestBody, operation
            );

            // then
            assertThat(result).isEqualTo(response);
            verify(idempotencyKeyRepository).save(any(IdempotencyKey.class));
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
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT), any(LocalDateTime.class)
            )).thenReturn(Optional.empty());

            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(secondKeyHash), eq(USER_ID), eq(ENDPOINT), any(LocalDateTime.class)
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
    @DisplayName("5. Очистка просроченных ключей")
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

    @Nested
    @DisplayName("6. Кросс-контекстные сценарии (разные пользователи/эндпоинты)")
    class CrossContextTests {

        @Test
        @DisplayName("Один и тот же ключ для разных пользователей - должен создавать разные движения")
        void sameKeyDifferentUsersShouldCreateDifferentMovements() throws Exception {
            // given
            Long secondUserId = 2L;
            String secondUsername = "another_admin";
            UserContext secondUserContext = new UserContext(secondUserId, secondUsername);
            User secondUser = User.builder()
                    .id(secondUserId)
                    .username(secondUsername)
                    .build();

            IdempotentRequestContext firstUserRequestContext = new IdempotentRequestContext(
                    IDEMPOTENCY_KEY, ENDPOINT, userContext
            );
            IdempotentRequestContext secondUserRequestContext = new IdempotentRequestContext(
                    IDEMPOTENCY_KEY, ENDPOINT, secondUserContext
            );

            String secondKeyHash = TokenHashUtil.hashToken(IDEMPOTENCY_KEY);

            // Первый пользователь — ключа нет
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT), any(LocalDateTime.class)
            )).thenReturn(Optional.empty());

            // Второй пользователь — ключа нет
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(secondKeyHash), eq(secondUserId), eq(ENDPOINT), any(LocalDateTime.class)
            )).thenReturn(Optional.empty());

            when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
            when(userRepository.getReferenceById(secondUserId)).thenReturn(secondUser);
            when(objectMapper.writeValueAsString(any(StockMovementResponse.class)))
                    .thenReturn("{}");

            Supplier<StockMovementResponse> operation = () -> response;

            // when
            StockMovementResponse result1 = idempotencyService.processIdempotentRequest(
                    firstUserRequestContext, requestBody, operation
            );
            StockMovementResponse result2 = idempotencyService.processIdempotentRequest(
                    secondUserRequestContext, requestBody, operation
            );

            // then
            assertThat(result1).isEqualTo(response);
            assertThat(result2).isEqualTo(response);
            verify(idempotencyKeyRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("Один и тот же ключ для разных эндпоинтов - должен создавать разные движения")
        void sameKeyDifferentEndpointsShouldCreateDifferentMovements() throws Exception {
            // given
            String secondEndpoint = "/api/movements/write-off";
            IdempotentRequestContext firstContext = new IdempotentRequestContext(
                    IDEMPOTENCY_KEY, ENDPOINT, userContext
            );
            IdempotentRequestContext secondContext = new IdempotentRequestContext(
                    IDEMPOTENCY_KEY, secondEndpoint, userContext
            );

            // Первый эндпоинт — ключа нет
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(KEY_HASH), eq(USER_ID), eq(ENDPOINT), any(LocalDateTime.class)
            )).thenReturn(Optional.empty());

            // Второй эндпоинт — ключа нет
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(KEY_HASH), eq(USER_ID), eq(secondEndpoint), any(LocalDateTime.class)
            )).thenReturn(Optional.empty());

            when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
            when(objectMapper.writeValueAsString(any(StockMovementResponse.class)))
                    .thenReturn("{}");

            Supplier<StockMovementResponse> operation = () -> response;

            // when
            StockMovementResponse result1 = idempotencyService.processIdempotentRequest(
                    firstContext, requestBody, operation
            );
            StockMovementResponse result2 = idempotencyService.processIdempotentRequest(
                    secondContext, requestBody, operation
            );

            // then
            assertThat(result1).isEqualTo(response);
            assertThat(result2).isEqualTo(response);
            verify(idempotencyKeyRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("Чужой пользователь с тем же ключом - не должен получить чужой результат")
        void differentUserWithSameKeyShouldNotGetCachedResult() throws Exception {
            // given
            Long firstUserId = 1L;
            Long secondUserId = 2L;
            User firstUser = User.builder().id(firstUserId).username("user1").build();
            User secondUser = User.builder().id(secondUserId).username("user2").build();

            UserContext firstUserCtx = new UserContext(firstUserId, "user1");
            UserContext secondUserCtx = new UserContext(secondUserId, "user2");

            IdempotentRequestContext firstContext = new IdempotentRequestContext(
                    IDEMPOTENCY_KEY, ENDPOINT, firstUserCtx
            );
            IdempotentRequestContext secondContext = new IdempotentRequestContext(
                    IDEMPOTENCY_KEY, ENDPOINT, secondUserCtx
            );

            String requestBodyHash = TokenHashUtil.hashToken("1:5");
            String responseJson = "{\"itemId\":1,\"movementId\":100,\"type\":\"RECEIVE\","
                    + "\"quantity\":5,\"stockAfter\":10,\"lowStockAlert\":false}";

            // Первый пользователь создает ключ
            IdempotencyKey existingKey = IdempotencyKey.builder()
                    .keyHash(KEY_HASH)
                    .user(firstUser)
                    .endpoint(ENDPOINT)
                    .requestBodyHash(requestBodyHash)
                    .responseBody(responseJson)
                    .statusCode(200)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();

            // Первый запрос — создает ключ
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(KEY_HASH), eq(firstUserId), eq(ENDPOINT), any(LocalDateTime.class)
            )).thenReturn(Optional.empty())
                    .thenReturn(Optional.of(existingKey));

            // Второй пользователь — ключа нет (это другой пользователь)
            when(idempotencyKeyRepository.findByKeyHashAndUserIdAndEndpoint(
                    eq(KEY_HASH), eq(secondUserId), eq(ENDPOINT), any(LocalDateTime.class)
            )).thenReturn(Optional.empty());

            when(userRepository.getReferenceById(firstUserId)).thenReturn(firstUser);
            when(userRepository.getReferenceById(secondUserId)).thenReturn(secondUser);

            // Мокаем сохранение для первого
            when(idempotencyKeyRepository.save(any(IdempotencyKey.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(objectMapper.writeValueAsString(any(StockMovementResponse.class)))
                    .thenReturn(responseJson);

            Supplier<StockMovementResponse> operation = () -> response;

            // Первый запрос — создает ключ
            StockMovementResponse firstResult = idempotencyService.processIdempotentRequest(
                    firstContext, requestBody, operation
            );

            // Второй запрос от другого пользователя — должен создать свой ключ
            StockMovementResponse secondResult = idempotencyService.processIdempotentRequest(
                    secondContext, requestBody, operation
            );

            // then
            assertThat(firstResult).isEqualTo(response);
            assertThat(secondResult).isEqualTo(response);
            verify(idempotencyKeyRepository, times(2)).save(any());
        }
    }
}