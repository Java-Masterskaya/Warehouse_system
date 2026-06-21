package com.warehouse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционные тесты для ItemController: все эндпоинты (GET, POST, PUT, DELETE).
 * Проверяют: фильтрацию, сортировку, пагинацию, безопасность, валидацию.
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

        // Создаём пользователя testuser, если его нет
        User testUser = userRepository.findByUsername("testuser").orElseGet(() -> {
            User user = new User();
            user.setUsername("testuser");
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole(com.warehouse.entity.Role.ROLE_USER);
            user.setActive(true);
            return userRepository.save(user);
        });

        userToken = jwtUtil.generateToken(testUser.getUsername(), testUser.getId(), List.of("ROLE_USER"));

        // Уникальный суффикс чтобы SKU не конфликтовали между запусками тестов
        String suffix = String.valueOf(System.currentTimeMillis());
        createItem("SKU-SORT-A-" + suffix, "Альфа", "Электроника");
        createItem("SKU-SORT-B-" + suffix, "Бета", "Электроника");
        createItem("SKU-SORT-C-" + suffix, "Dell Laptop", "Компьютеры");
        createItem("SKU-SORT-D-" + suffix, "DELL Monitor", "Компьютеры");
    }

    /**
     * Сортировка по SKU по убыванию: первый SKU больше или равен второму.
     */
    @Test
    void getItemsSortBySkuDescFirstSkuGreaterThanSecond() throws Exception {
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
     * Фильтрация по категории возвращает только совпадающие товары.
     */
    @Test
    void getItemsFilterByCategoryReturnsOnlyMatchingItems() throws Exception {
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
     * Поиск по имени без учёта регистра возвращает совпадения.
     */
    @Test
    void getItemsSearchByNameCaseInsensitiveReturnsMatches() throws Exception {
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
    void getItemsSearchByNameUppercaseReturnsSameResultsAsLowercase() throws Exception {
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
    void getItemsPaginationReturnsCorrectPageSize() throws Exception {
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
    void getItemsResponseContainsRequiredFields() throws Exception {
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
     * USER токен может получить список товаров.
     */
    @Test
    void getItemsUserTokenCanAccess() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }

    /**
     * Запрос без токена возвращает 401 Unauthorized.
     */
    @Test
    void getItemsNoTokenReturns401() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * Создание товара администратором возвращает 201 Created с телом.
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
     * Создание товара с дублирующимся SKU возвращает 409 Conflict.
     */
    @Test
    void createItemDuplicateSkuReturns409() throws Exception {
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-DUP", "Товар", "Категория", 0);

        // Создаём первый раз — успешно
        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Тот же SKU — должен вернуть 409
        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_SKU"));
    }

    /**
     * Создание товара без токена возвращает 401 Unauthorized.
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
     * Создание товара пользовательским токеном возвращает 403 Forbidden.
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
     * Создание товара с пустым SKU возвращает 400 Bad Request.
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
     * Создание товара с отрицательным minStock возвращает 400 Bad Request.
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
     * Обновление товара администратором возвращает 200 OK.
     */
    @Test
    void updateItemAdminTokenReturns200() throws Exception {
        Long itemId = 1L;
        UpdateItemRequest request = new UpdateItemRequest("Новое название", "Новая категория", 10);

        mockMvc.perform(put("/api/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Новое название"))
                .andExpect(jsonPath("$.category").value("Новая категория"))
                .andExpect(jsonPath("$.minStock").value(10));
    }

    /**
     * Обновление товара пользовательским токеном возвращает 403 Forbidden.
     */
    @Test
    void updateItemUserTokenReturns403() throws Exception {
        Long itemId = 1L;
        UpdateItemRequest request = new UpdateItemRequest("Новое название", "Новая категория", 10);

        mockMvc.perform(put("/api/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    /**
     * Обновление товара без токена возвращает 401 Unauthorized.
     */
    @Test
    void updateItemNoTokenReturns401() throws Exception {
        Long itemId = 1L;
        UpdateItemRequest request = new UpdateItemRequest("Новое название", "Новая категория", 10);

        mockMvc.perform(put("/api/items/{itemId}", itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * Обновление несуществующего товара возвращает 404 Not Found.
     */
    @Test
    void updateItemNotFoundReturns404() throws Exception {
        Long itemId = 999L;
        UpdateItemRequest request = new UpdateItemRequest("Новое название", "Новая категория", 10);

        mockMvc.perform(put("/api/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    /**
     * Обновление товара с пустым названием возвращает 400 Bad Request.
     */
    @Test
    void updateItemBlankNameReturns400() throws Exception {
        Long itemId = 1L;
        String body = """
                {"name": "", "category": "Категория", "minStock": 10}
                """;

        mockMvc.perform(put("/api/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    /**
     * Получение детальной информации о товаре администратором возвращает 200 OK.
     */
    @Test
    void getItemAdminTokenReturns200() throws Exception {
        Long itemId = 1L;

        mockMvc.perform(get("/api/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.sku").isNotEmpty())
                .andExpect(jsonPath("$.name").isNotEmpty())
                .andExpect(jsonPath("$.category").isNotEmpty())
                .andExpect(jsonPath("$.currentStock").isNumber())
                .andExpect(jsonPath("$.isActive").isBoolean());
    }

    /**
     * USER токен может получить детальную информацию о товаре.
     */
    @Test
    void getItemUserTokenCanAccess() throws Exception {
        Long itemId = 1L;

        mockMvc.perform(get("/api/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }

    /**
     * Получение несуществующего товара возвращает 404 Not Found.
     */
    @Test
    void getItemNotFoundReturns404() throws Exception {
        Long itemId = 999L;

        mockMvc.perform(get("/api/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Удаление товара администратором возвращает 204 No Content.
     */
    @Test
    void deleteItemAdminTokenReturns204() throws Exception {
        Long itemId = 1L;

        mockMvc.perform(delete("/api/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    /**
     * Удаление товара пользовательским токеном возвращает 403 Forbidden.
     */
    @Test
    void deleteItemUserTokenReturns403() throws Exception {
        Long itemId = 1L;

        mockMvc.perform(delete("/api/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    /**
     * Удаление товара без токена возвращает 401 Unauthorized.
     */
    @Test
    void deleteItemNoTokenReturns401() throws Exception {
        Long itemId = 1L;

        mockMvc.perform(delete("/api/items/{itemId}", itemId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * Удаление несуществующего товара возвращает 404 Not Found.
     */
    @Test
    void deleteItemNotFoundReturns404() throws Exception {
        Long itemId = 999L;

        mockMvc.perform(delete("/api/items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Вспомогательный метод для создания товара в базе.
     *
     * @param sku      SKU товара
     * @param name     название товара
     * @param category категория товара
     */
    private void createItem(String sku, String name, String category) throws Exception {
        CreateItemRequest request = new CreateItemRequest(sku, name, category, 0);
        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    /**
     * Вспомогательный метод для получения JWT токена через API.
     *
     * @param username имя пользователя
     * @param password пароль
     * @return JWT токен
     */
    private String obtainToken(String username, String password) throws Exception {
        LoginRequest request = new LoginRequest(username, password);
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }
}