package com.warehouse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.response.PageResponse;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.entity.User;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.JwtUtil;
import com.warehouse.service.item.ItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Интеграционный тест HTTP-слоя: реальный Spring-контекст + Testcontainers (из AbstractIntegrationTest).
// Проверяет: статусы ответов, тело JSON, Security (401/403).
@AutoConfigureMockMvc
class ItemControllerTest extends AbstractIntegrationTest {

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
        // Получаем реальный токен через /api/auth/login — admin создаётся миграцией V5
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

        // USER нет в миграциях — генерируем токен напрямую.
        // JwtAuthFilter читает роли из claims, не обращаясь к БД, поэтому это корректно.
        userToken = jwtUtil.generateToken(testUser.getUsername(), testUser.getId(), List.of("ROLE_USER"));
    }

    // --- Acceptance criteria задачи #1 ---

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

    @Test
    void createItemNoTokenReturns401() throws Exception {
        CreateItemRequest request = new CreateItemRequest("SKU-CTRL-002", "Товар", "Категория", 0);

        mockMvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

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

    // --- Валидация входных данных ---

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

    // Вспомогательный метод для получения токена через /api/auth/login
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
// ==========================================
// UNIT ТЕСТЫ С @TestConfiguration
// ==========================================

    @WebMvcTest(ItemController.class)
    class ItemControllerSecurityUnitTest {

        @TestConfiguration
        static class TestConfig {
            @Bean
            @Primary
            public ItemService itemService() {
                return Mockito.mock(ItemService.class);
            }
        }

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private ItemService itemService;

        @Nested
        @DisplayName("POST /api/items - Security Tests")
        class CreateItemSecurityTests {

            @Test
            @WithMockUser(roles = "ADMIN")
            @DisplayName("ADMIN может создать товар - 201")
            void adminCanCreateItem() throws Exception {
                CreateItemRequest request = new CreateItemRequest("SKU-UNIT-001", "Test", "Category", 10);
                ItemResponse response = new ItemResponse(1L, "SKU-UNIT-001", "Test", "Category", 10, true, LocalDateTime.now());

                when(itemService.createItem(any(CreateItemRequest.class))).thenReturn(response);

                mockMvc.perform(post("/api/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.sku").value("SKU-UNIT-001"));
            }

            @Test
            @WithMockUser(roles = "USER")
            @DisplayName("USER не может создать товар - 403")
            void userCannotCreateItem() throws Exception {
                CreateItemRequest request = new CreateItemRequest("SKU-UNIT-002", "Test", "Category", 10);

                mockMvc.perform(post("/api/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isForbidden());
            }

            @Test
            @DisplayName("Без токена - 401")
            void noTokenReturns401() throws Exception {
                CreateItemRequest request = new CreateItemRequest("SKU-UNIT-003", "Test", "Category", 10);

                mockMvc.perform(post("/api/items")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isUnauthorized());
            }
        }

        @Nested
        @DisplayName("PUT /api/items/{id} - Security Tests")
        class UpdateItemSecurityTests {

            @Test
            @WithMockUser(roles = "ADMIN")
            @DisplayName("ADMIN может обновить товар - 200")
            void adminCanUpdateItem() throws Exception {
                UpdateItemRequest request = new UpdateItemRequest("Updated", "Category", 20);
                ItemResponse response = new ItemResponse(1L, "SKU-001", "Updated", "Category", 20, true, LocalDateTime.now());

                when(itemService.updateItem(eq(1L), any(UpdateItemRequest.class))).thenReturn(response);

                mockMvc.perform(put("/api/items/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.name").value("Updated"));
            }

            @Test
            @WithMockUser(roles = "USER")
            @DisplayName("USER не может обновить товар - 403")
            void userCannotUpdateItem() throws Exception {
                UpdateItemRequest request = new UpdateItemRequest("Updated", "Category", 20);

                mockMvc.perform(put("/api/items/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isForbidden());
            }
        }

        @Nested
        @DisplayName("DELETE /api/items/{id} - Security Tests")
        class DeleteItemSecurityTests {

            @Test
            @WithMockUser(roles = "ADMIN")
            @DisplayName("ADMIN может удалить товар - 204")
            void adminCanDeleteItem() throws Exception {
                mockMvc.perform(delete("/api/items/1"))
                        .andExpect(status().isNoContent());
            }

            @Test
            @WithMockUser(roles = "USER")
            @DisplayName("USER не может удалить товар - 403")
            void userCannotDeleteItem() throws Exception {
                mockMvc.perform(delete("/api/items/1"))
                        .andExpect(status().isForbidden());
            }
        }

        @Nested
        @DisplayName("GET /api/items - Security Tests")
        class GetItemsSecurityTests {

            @Test
            @WithMockUser(roles = "ADMIN")
            @DisplayName("ADMIN может получить список товаров - 200")
            void adminCanGetItems() throws Exception {
                PageResponse<ItemResponse> response = new PageResponse<>(List.of(), 0L, 0, 0, 20);
                when(itemService.getItems(anyString(), anyString(), any(), any(), anyInt(), anyInt()))
                        .thenReturn(response);

                mockMvc.perform(get("/api/items"))
                        .andExpect(status().isOk());
            }

            @Test
            @WithMockUser(roles = "USER")
            @DisplayName("USER может получить список товаров - 200")
            void userCanGetItems() throws Exception {
                PageResponse<ItemResponse> response = new PageResponse<>(List.of(), 0L, 0, 0, 20);
                when(itemService.getItems(anyString(), anyString(), any(), any(), anyInt(), anyInt()))
                        .thenReturn(response);

                mockMvc.perform(get("/api/items"))
                        .andExpect(status().isOk());
            }
        }

        @Nested
        @DisplayName("GET /api/items/{id} - Security Tests")
        class GetItemSecurityTests {

            @Test
            @WithMockUser(roles = "ADMIN")
            @DisplayName("ADMIN может получить товар - 200")
            void adminCanGetItem() throws Exception {
                ItemDetailsResponse response = new ItemDetailsResponse(
                        1L, "SKU-001", "Test", "Category", 10, 50, true, LocalDateTime.now(), LocalDateTime.now()
                );
                when(itemService.getItem(1L)).thenReturn(response);

                mockMvc.perform(get("/api/items/1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.id").value(1));
            }

            @Test
            @WithMockUser(roles = "USER")
            @DisplayName("USER может получить товар - 200")
            void userCanGetItem() throws Exception {
                ItemDetailsResponse response = new ItemDetailsResponse(
                        1L, "SKU-001", "Test", "Category", 10, 50, true, LocalDateTime.now(), LocalDateTime.now()
                );
                when(itemService.getItem(1L)).thenReturn(response);

                mockMvc.perform(get("/api/items/1"))
                        .andExpect(status().isOk());
            }
        }
    }
}