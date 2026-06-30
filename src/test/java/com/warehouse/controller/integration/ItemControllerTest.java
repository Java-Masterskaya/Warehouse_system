package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.User;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.JwtUtil;
import com.warehouse.service.item.ItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    private ItemRepository itemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ItemService itemService;


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
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-001", "Ноутбук Dell", "Электроника", 5, BigDecimal.valueOf(1500.00), BigDecimal.valueOf(1000.00));

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
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-DUP", "Товар", "Категория", 0, BigDecimal.valueOf(100.00), BigDecimal.valueOf(50.00));

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
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-002", "Товар", "Категория", 0, BigDecimal.valueOf(100.00), BigDecimal.valueOf(50.00));

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
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-003", "Товар", "Категория", 0, BigDecimal.valueOf(100.00), BigDecimal.valueOf(50.00));

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
                {"sku": "", "name": "Товар", "category": "Категория", "minStock": 0, "price": 100.00, "cost": 50.00}
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
                {"sku": "SKU-CTRL-004", "name": "Товар", "category": "Категория", "minStock": -1, "price": 100.00, "cost": 50.00}
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

    /**
     * Тесты для карточки товара с новыми полями price и cost.
     */
    /**
     * Карточка товара содержит поля price и cost.
     */
    @Test
    void itemCardContainsPriceAndCost() throws Exception {
        String sku = "SKU-PRICE-TEST-" + System.currentTimeMillis();
        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"sku\": \"" + sku + "\"," +
                                "\"name\": \"Товар с ценой\"," +
                                "\"category\": \"Тест\"," +
                                "\"minStock\": 5," +
                                "\"price\": 1500.995," +
                                "\"cost\": 1000.495" +
                                "}"))
                .andExpect(status().isCreated());

        ItemDetailsResponse response = itemService.getItem(itemRepository.findBySku(sku).get().getId());

        assertThat(response.price().compareTo(BigDecimal.valueOf(1501.00))).isEqualTo(0);
        assertThat(response.cost().compareTo(BigDecimal.valueOf(1000.50))).isEqualTo(0);
    }

    /**
     * Валидация: отрицательная цена возвращает 400 Bad Request.
     */
    @Test
    void createItemNegativePriceReturns400() throws Exception {
        String body = """
                {"sku": "SKU-NEG-PRICE", "name": "Товар", "category": "Категория", "minStock": 0, "price": -100.00, "cost": 50.00}
                """;

        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    /**
     * Валидация: отрицательная себестоимость возвращает 400 Bad Request.
     */
    @Test
    void createItemNegativeCostReturns400() throws Exception {
        String body = """
                {"sku": "SKU-NEG-COST", "name": "Товар", "category": "Категория", "minStock": 0, "price": 100.00, "cost": -50.00}
                """;

        mockMvc.perform(post("/api/items")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    /**
     * Обновление товара с отрицательной ценой возвращает 400 Bad Request.
     */
    @Test
    void updateItemNegativePriceReturns400() throws Exception {
        String sku = "SKU-UPDATE-TEST-" + System.currentTimeMillis();
        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"sku\": \"" + sku + "\"," +
                                "\"name\": \"Товар для обновления\"," +
                                "\"category\": \"Тест\"," +
                                "\"minStock\": 5," +
                                "\"price\": 100.00," +
                                "\"cost\": 50.00" +
                                "}"))
                .andExpect(status().isCreated());

        Long itemId = itemRepository.findBySku(sku).get().getId();

        String updateBody = """
                {"name": "Обновленный товар", "category": "Тест", "minStock": 10, "price": -100.00, "cost": 50.00}
                """;

        mockMvc.perform(put("/api/items/" + itemId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    /**
     * Обновление товара с отрицательной себестоимостью возвращает 400 Bad Request.
     */
    @Test
    void updateItemNegativeCostReturns400() throws Exception {
        String sku = "SKU-UPDATE-TEST-2" + System.currentTimeMillis();
        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"sku\": \"" + sku + "\"," +
                                "\"name\": \"Товар для обновления\"," +
                                "\"category\": \"Тест\"," +
                                "\"minStock\": 5," +
                                "\"price\": 100.00," +
                                "\"cost\": 50.00" +
                                "}"))
                .andExpect(status().isCreated());

        Long itemId = itemRepository.findBySku(sku).get().getId();

        String updateBody = """
                {"name": "Обновленный товар", "category": "Тест", "minStock": 10, "price": 100.00, "cost": -50.00}
                """;

        mockMvc.perform(put("/api/items/" + itemId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    /**
     * Цена и себестоимость округляются до 2 знаков после запятой.
     */
    @Test
    void priceAndCostRoundingWorksCorrectly() throws Exception {
        String sku = "SKU-ROUNDING-TEST-" + System.currentTimeMillis();
        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"sku\": \"" + sku + "\"," +
                                "\"name\": \"Товар с округлением\"," +
                                "\"category\": \"Тест\"," +
                                "\"minStock\": 5," +
                                "\"price\": 1500.999," +
                                "\"cost\": 1000.444" +
                                "}"))
                .andExpect(status().isCreated());

        Item item = itemRepository.findBySku(sku).get();

        assertThat(item.getPrice().compareTo(BigDecimal.valueOf(1501.00))).isEqualTo(0);
        assertThat(item.getCost().compareTo(BigDecimal.valueOf(1000.44))).isEqualTo(0);

        ItemDetailsResponse response = itemService.getItem(item.getId());
        assertThat(response.price().compareTo(BigDecimal.valueOf(1501.00))).isEqualTo(0);
        assertThat(response.cost().compareTo(BigDecimal.valueOf(1000.44))).isEqualTo(0);
    }

    private void createItem(String sku, String name, String category) throws Exception {
        CreateItemRequest request = new CreateItemRequest(sku, name, category, 0, BigDecimal.valueOf(100.00), BigDecimal.valueOf(50.00));
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
