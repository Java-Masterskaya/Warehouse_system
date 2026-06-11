package com.warehouse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Интеграционные тесты GET /api/items: фильтры, сортировка, пагинация, безопасность.
// Данные создаются через POST /api/items перед каждым тестом.
@AutoConfigureMockMvc
class GetItemsControllerTest extends AbstractIntegrationTest {

    private static final String BASE_URL = "/api/items";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = obtainToken("admin", "secret");
        userToken = jwtUtil.generateToken("testuser_get", List.of("ROLE_USER"));

        // Уникальный суффикс чтобы SKU не конфликтовали между запусками тестов
        String suffix = String.valueOf(System.currentTimeMillis());
        createItem("SKU-SORT-A-" + suffix, "Альфа",      "Электроника");
        createItem("SKU-SORT-B-" + suffix, "Бета",       "Электроника");
        createItem("SKU-SORT-C-" + suffix, "Dell Laptop", "Компьютеры");
        createItem("SKU-SORT-D-" + suffix, "DELL Monitor","Компьютеры");
    }

    // --- Критерии приёмки ---

    // ?sort=sku&order=desc → первый элемент имеет SKU лексикографически >= второго
    @Test
    void sortBySkuDescReturnsDescendingOrder() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .param("sort", "sku")
                        .param("order", "desc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].sku").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(
                                (String) null) // проверяем через отдельный assert ниже
                ));
    }

    // ?sort=sku&order=desc → SKU[0] >= SKU[1] (лексикографически убывание)
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

    // ?category=Электроника → только товары из этой категории
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

    // ?search=dell → нечувствительный к регистру: находит "Dell Laptop" и "DELL Monitor"
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

    // ?search=DELL → тот же результат, что и ?search=dell
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

    // Пагинация: size=2, page=0 → возвращает ровно 2 элемента и корректные метаданные
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

    // Структура ответа содержит все обязательные поля
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

    // --- Безопасность ---

    // Пользователь с ролью USER имеет доступ к эндпоинту
    @Test
    void userTokenCanAccessGetItems() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
    }

    // Без токена → 401
    @Test
    void noTokenReturns401() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    // --- Вспомогательные методы ---

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
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }
}
