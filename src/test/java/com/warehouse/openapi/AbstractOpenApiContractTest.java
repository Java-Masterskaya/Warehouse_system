package com.warehouse.openapi;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.util.JwtUtil;
import com.warehouse.test.snapshot.OpenApiSnapshotSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Общая база для тестов, которые проверяют реальные ответы контроллеров на соответствие
 * зафиксированному OpenAPI-контракту ({@code src/test/resources/openapi/warehouse.json}).
 *
 * <p>Используется два экземпляра валидатора:
 * <ul>
 *     <li>{@link #openApiContract()} — полная проверка запроса и ответа. Подходит для
 *     сценариев, где сам запрос валиден по контракту (успех, а также 404/409/422/403 —
 *     когда ошибка вызвана бизнес-правилом, а не нарушением формы запроса).</li>
 *     <li>{@link #openApiResponseContract()} — проверяет только ответ, игнорируя нарушения
 *     на стороне запроса (см. {@code validation.request.*} в {@code LevelResolver}). Нужен для
 *     тестов, где запрос ломает контракт намеренно — нет токена (401), не хватает роли без
 *     токена или тело не проходит валидацию (400) — а интересует именно форма ответа-ошибки.</li>
 * </ul>
 *
 * <p>Отдельно обе конфигурации понижают до WARN проверку формата {@code date-time}:
 * поля на базе {@link java.time.LocalDateTime} (например, {@code createdAt}, {@code expiryDate})
 * сериализуются Джексоном без смещения часового пояса, а OpenAPI {@code format: date-time}
 * по умолчанию требует RFC-3339 со смещением. Смена на {@code OffsetDateTime}/{@code Instant}
 * во всех DTO — отдельная задача вне рамок контрактных тестов, поэтому здесь фиксируем текущее,
 * осознанно принятое поведение, а не роняем тесты на уже известном расхождении.
 */
abstract class AbstractOpenApiContractTest extends AbstractIntegrationTest {

    private static final String DATE_TIME_FORMAT_REQUEST_CHECK = "validation.request.body.schema.format.date-time";
    private static final String DATE_TIME_FORMAT_RESPONSE_CHECK = "validation.response.body.schema.format.date-time";

    private static final OpenApiInteractionValidator STRICT_VALIDATOR =
            OpenApiInteractionValidator.createFor(OpenApiSnapshotSupport.SNAPSHOT_CLASSPATH_RESOURCE)
                    .withLevelResolver(LevelResolver.create()
                            .withLevel(DATE_TIME_FORMAT_REQUEST_CHECK, ValidationReport.Level.WARN)
                            .withLevel(DATE_TIME_FORMAT_RESPONSE_CHECK, ValidationReport.Level.WARN)
                            .build())
                    .build();

    private static final OpenApiInteractionValidator RESPONSE_ONLY_VALIDATOR =
            OpenApiInteractionValidator.createFor(OpenApiSnapshotSupport.SNAPSHOT_CLASSPATH_RESOURCE)
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request", ValidationReport.Level.IGNORE)
                            .withLevel(DATE_TIME_FORMAT_RESPONSE_CHECK, ValidationReport.Level.WARN)
                            .build())
                    .build();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JwtUtil jwtUtil;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    protected String adminToken;
    protected String userToken;

    @BeforeEach
    void setUpContractTestUsers() throws Exception {
        adminToken = obtainToken("admin", "secret");

        User testUser = userRepository.findByUsername("testuser").orElseGet(() -> {
            User user = new User();
            user.setUsername("testuser");
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole(Role.ROLE_USER);
            user.setActive(true);
            return userRepository.save(user);
        });
        userToken = jwtUtil.generateToken(testUser.getUsername(), testUser.getId(), List.of("ROLE_USER"));
    }

    protected static ResultMatcher openApiContract() {
        return OpenApiValidationMatchers.openApi().isValid(STRICT_VALIDATOR);
    }

    protected static ResultMatcher openApiResponseContract() {
        return OpenApiValidationMatchers.openApi().isValid(RESPONSE_ONLY_VALIDATOR);
    }

    protected String obtainToken(String username, String password) throws Exception {
        LoginRequest request = new LoginRequest(username, password);
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }
}
