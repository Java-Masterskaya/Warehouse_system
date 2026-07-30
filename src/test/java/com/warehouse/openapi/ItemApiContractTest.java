package com.warehouse.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.entity.Category;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет, что ответы {@code /api/items/**} соответствуют зафиксированному OpenAPI-контракту:
 * и успешные (200/201), и ошибочные (400/401/403/404/409) ответы должны совпадать со схемами
 * из {@code src/test/resources/openapi/warehouse.json}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ItemApiContractTest extends AbstractOpenApiContractTest {

    private static final String BASE_URL = "/api/items";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @BeforeEach
    void createCategory() {
        if (!categoryRepository.existsByNameIgnoreCase("Контракт")) {
            categoryRepository.save(Category.builder().name("Контракт").build());
        }
    }

    @Test
    void createItemSuccessMatchesContract() throws Exception {
        CreateItemRequest request = new CreateItemRequest(
                "SKU-CONTRACT-" + System.currentTimeMillis(), "Товар", "Контракт",
                5, BigDecimal.valueOf(100.00), BigDecimal.valueOf(50.00));

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(openApiContract());
    }

    @Test
    void createItemDuplicateSkuMatchesContract() throws Exception {
        CreateItemRequest request = new CreateItemRequest(
                "SKU-CONTRACT-DUP-" + System.currentTimeMillis(), "Товар", "Контракт",
                0, BigDecimal.valueOf(100.00), BigDecimal.valueOf(50.00));

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(openApiContract());
    }

    @Test
    void createItemValidationErrorMatchesContract() throws Exception {
        String body = """
                {"sku": "", "name": "Товар", "category": "Контракт", "minStock": 0,
                 "price": 100.00, "cost": 50.00}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(openApiResponseContract());
    }

    @Test
    void createItemNoTokenMatchesContract() throws Exception {
        CreateItemRequest request = new CreateItemRequest(
                "SKU-CONTRACT-NOAUTH-" + System.currentTimeMillis(), "Товар", "Контракт",
                0, BigDecimal.valueOf(100.00), BigDecimal.valueOf(50.00));

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(openApiResponseContract());
    }

    @Test
    void createItemUserTokenMatchesContract() throws Exception {
        CreateItemRequest request = new CreateItemRequest(
                "SKU-CONTRACT-FORBIDDEN-" + System.currentTimeMillis(), "Товар", "Контракт",
                0, BigDecimal.valueOf(100.00), BigDecimal.valueOf(50.00));

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(openApiContract());
    }

    @Test
    void getItemListMatchesContract() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(openApiContract());
    }

    @Test
    void getItemByIdMatchesContract() throws Exception {
        String sku = "SKU-CONTRACT-GET-" + System.currentTimeMillis();
        CreateItemRequest request = new CreateItemRequest(
                sku, "Товар", "Контракт", 0, BigDecimal.valueOf(100.00), BigDecimal.valueOf(50.00));
        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        Long itemId = itemRepository.findBySku(sku).orElseThrow().getId();

        mockMvc.perform(get(BASE_URL + "/" + itemId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(openApiContract());
    }

    @Test
    void getNonExistentItemMatchesContract() throws Exception {
        mockMvc.perform(get(BASE_URL + "/999999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(openApiContract());
    }
}
