package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.entity.User;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционный тест для проверки эндпоинтов управления товарами.
 * Тестирует POST /api/items (создание) и GET /api/items (список с фильтрацией, сортировкой, пагинацией).
 */
@AutoConfigureMockMvc
class ItemControllerTest extends AbstractIntegrationTest {

    private static final String BASE_URL = "/api/items";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = obtainToken("admin", "secret");

        User testUser = userRepository.findByUsername("testuser").orElseGet(() -> {
            User user = new User();
            user.setUsername("testuser");
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole(com.warehouse.entity.Role.ROLE_USER);
            user.setActive(true);
            return userRepository.save(user);
        });

        userToken = jwtUtil.generateToken(testUser.getUsername(), testUser.getId(), List.of("ROLE_USER"));

        String suffix = String.valueOf(System.currentTimeMillis());
        createItem("SKU-SORT-A-" + suffix, "Альфа", "Электроника");
        createItem("SKU-SORT-B-" + suffix, "Бета", "Электроника");
        createItem("SKU-SORT-C-" + suffix, "Dell Laptop", "Компьютеры");
        createItem("SKU-SORT-D-" + suffix, "DELL Monitor", "Компьютеры");
    }

    /**
     * ADMIN может создать товар и получить 201 CREATED с телом ответа.
     */
    @Test
    void createItemAdminTokenReturns201WithBody() throws Exception {
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-001", "Ноутбук Dell", "Электроника", 5);

        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-CTRL-001"))
                .andExpect(jsonPath("$.name").value("Ноутбук Dell"))
                .andExpect(jsonPath("$.isActive").value(true));
    }

    /**
     * Попытка создать товар с дублирующимся SKU возвращает 409 Conflict.
     */
    @Test
    void createItemDuplicateSkuReturns409() throws Exception {
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-DUP", "Товар", "Категория", 0);

        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_SKU"));
    }

    /**
     * Запрос без токена создать товар возвращает 401 Unauthorized.
     */
    @Test
    void createItemNoTokenReturns401() throws Exception {
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-002", "Товар", "Категория", 0);

        mockMvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * USER не может создать товар (доступ запрещен), возвращает 403 Forbidden.
     */
    @Test
    void createItemUserTokenReturns403() throws Exception {
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-003", "Товар", "Категория", 0);

        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    /**
     * Валидация: пустой SKU возвращает 400 Bad Request.
     */
    @Test
    void createItemBlankSkuReturns400() throws Exception {
        String body = """
                {"sku": "", "name": "Товар", "category": "Категория", "minStock": 0}
                """;

        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    /**
     * Валидация: отрицательный minStock возвращает 400 Bad Request.
     */
    @Test
    void createItemNegativeMinStockReturns400() throws Exception {
        String body = """
                {"sku": "SKU-CTRL-004", "name": "Товар", "category": "Категория", "minStock": -1}
                """;

        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    /**
     * Сортировка по SKU по убыванию: первый SKU больше или равен второму.
     */
    @Test
    void sortBySkuDescFirstSkuGreaterThanSecond() throws Exception {
        String response = mockMvc.perform(get(BASE_URL)
                        .param("sort", "sku")
                        .param("order", "desc")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var tree = objectMapper.readTree(response);
        var content = tree.get("content");
        if (content.size() >= 2) {
            String first = content.get(0).get("sku").asText();
            String second = content.get(1).get("sku").asText();
            org.assertj.core.api.Assertions.assertThat(first.compareTo(second)).isGreaterThanOrEqualTo(0);
        }
    }

    /**
     * Фильтрация по категории возвращает только товары этой категории.
     */
    @Test
    void filterByCategoryReturnsOnlyMatchingItems() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .param("category", "Электроника")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].category",
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is("Электроника"))));
    }

    /**
     * Поиск по имени (без учета регистра) возвращает совпадения.
     */
    @Test
    void searchByNameCaseInsensitiveReturnsMatches() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .param("search", "dell")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name",
                        org.hamcrest.Matchers.hasItems(
                                org.hamcrest.Matchers.containsStringIgnoringCase("dell"),
                                org.hamcrest.Matchers.containsStringIgnoringCase("dell"))));
    }

    /**
     * Поиск по имени в верхнем регистре возвращает те же результаты, что и в нижнем.
     */
    @Test
    void searchByNameUppercaseReturnsMatches() throws Exception {
        String lowerResult = mockMvc.perform(get(BASE_URL)
                        .param("search", "dell")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String upperResult = mockMvc.perform(get(BASE_URL)
                        .param("search", "DELL")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var lower = objectMapper.readTree(lowerResult).get("totalElements").asLong();
        var upper = objectMapper.readTree(upperResult).get("totalElements").asLong();
        org.assertj.core.api.Assertions.assertThat(lower).isEqualTo(upper);
    }

    /**
     * Пагинация возвращает корректный размер страницы.
     */
    @Test
    void paginationReturnsCorrectPageSize() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .param("page", "0")
                        .param("size", "2")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(lessThanOrEqualTo(2))))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalPages").isNumber())
                .andExpect(jsonPath("$.totalElements").isNumber());
    }

    /**
     * Ответ содержит обязательные поля (content, totalElements, totalPages, page, size).
     */
    @Test
    void responseContainsRequiredFields() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber())
                .andExpect(jsonPath("$.page").isNumber())
                .andExpect(jsonPath("$.size").isNumber());
    }

    /**
     * USER может получить список товаров (GET /api/items).
     */
    @Test
    void userTokenCanAccessGetItems() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }

    /**
     * Запрос без токена получить список товаров возвращает 401 Unauthorized.
     */
    @Test
    void noTokenReturns401() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    private void createItem(String sku, String name, String category) throws Exception {
        CreateItemRequest request = new CreateItemRequest(sku, name, category, 0);
        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private String obtainToken(String username, String password) throws Exception {
        LoginRequest request = new LoginRequest(username, password);
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }
}
