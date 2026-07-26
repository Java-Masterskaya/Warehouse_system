package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.category.CreateCategoryRequest;
import com.warehouse.dto.request.category.UpdateCategoryRequest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционные тесты эндпоинтов управления категориями.
 * <p>
 * Проверяет CRUD категорий, права доступа и запрет удаления
 * категории, которая используется товарами.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CategoryControllerTest extends AbstractIntegrationTest {

    private static final String BASE_URL = "/api/categories";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ItemRepository itemRepository;

    private String adminToken;
    private String userToken;
    private String suffix;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.findByUsername("admin").orElseGet(() -> {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("secret"));
            admin.setRole(Role.ROLE_ADMIN);
            admin.setActive(true);
            return userRepository.saveAndFlush(admin);
        });

        adminToken = obtainToken("admin", "secret");

        User testUser = userRepository.findByUsername("category-test-user").orElseGet(() -> {
            User user = new User();
            user.setUsername("category-test-user");
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole(Role.ROLE_USER);
            user.setActive(true);
            return userRepository.save(user);
        });

        userToken = jwtUtil.generateToken(testUser.getUsername(), testUser.getId(), List.of("ROLE_USER"));

        suffix = String.valueOf(System.currentTimeMillis());
    }

    /**
     * ADMIN может создать категорию.
     */
    @Test
    void createCategoryAdminReturns201()
            throws Exception {
        String categoryName = "Созданная категория-" + suffix;
        CreateCategoryRequest request = new CreateCategoryRequest(categoryName);

        mockMvc.perform(post(BASE_URL).header("Authorization", "Bearer " + adminToken)
                                      .contentType(MediaType.APPLICATION_JSON)
                                      .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.id").isNumber())
               .andExpect(jsonPath("$.name").value(categoryName));

        assertThat(categoryRepository.existsByNameIgnoreCase(categoryName)).isTrue();
    }

    /**
     * ADMIN может получить категорию по идентификатору.
     */
    @Test
    void getCategoryAdminReturns200()
            throws Exception {
        Category category = saveCategory("Категория для получения-" + suffix);

        mockMvc.perform(get(BASE_URL + "/" + category.getId()).header("Authorization", "Bearer " + adminToken))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.id").value(category.getId()))
               .andExpect(jsonPath("$.name").value(category.getName()));
    }

    /**
     * ADMIN может изменить название категории.
     */
    @Test
    void updateCategoryAdminReturns200()
            throws Exception {
        Category category = saveCategory("Категория до обновления-" + suffix);

        String updatedName = "Категория после обновления-" + suffix;

        UpdateCategoryRequest request = new UpdateCategoryRequest(updatedName);

        mockMvc.perform(put(BASE_URL + "/" + category.getId()).header("Authorization", "Bearer " + adminToken)
                                                              .contentType(MediaType.APPLICATION_JSON)
                                                              .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.id").value(category.getId()))
               .andExpect(jsonPath("$.name").value(updatedName));

        Category updatedCategory = categoryRepository.findById(category.getId()).orElseThrow();

        assertThat(updatedCategory.getName()).isEqualTo(updatedName);
    }

    /**
     * ADMIN может удалить категорию, которая не используется товарами.
     */
    @Test
    void deleteUnusedCategoryAdminReturns204()
            throws Exception {
        Category category = saveCategory("Категория для удаления-" + suffix);

        mockMvc.perform(delete(BASE_URL + "/" + category.getId()).header("Authorization", "Bearer " + adminToken))
               .andExpect(status().isNoContent());

        assertThat(categoryRepository.existsById(category.getId())).isFalse();
    }

    /**
     * Удаление категории, используемой товаром,
     * возвращает 409 Conflict.
     */
    @Test
    void deleteCategoryInUseReturns409()
            throws Exception {
        Category category = saveCategory("Используемая категория-" + suffix);

        Item item = saveItem(category);

        mockMvc.perform(delete(BASE_URL + "/" + category.getId()).header("Authorization", "Bearer " + adminToken))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.error").value("CATEGORY_IN_USE"));

        assertThat(categoryRepository.existsById(category.getId())).isTrue();

        assertThat(itemRepository.existsById(item.getId())).isTrue();
    }

    /**
     * Создание категории с существующим названием
     * возвращает 409 Conflict.
     */
    @Test
    void createDuplicateCategoryReturns409()
            throws Exception {
        Category category = saveCategory("Дублирующаяся категория-" + suffix);

        CreateCategoryRequest request = new CreateCategoryRequest(category.getName());

        mockMvc.perform(post(BASE_URL).header("Authorization", "Bearer " + adminToken)
                                      .contentType(MediaType.APPLICATION_JSON)
                                      .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.error").value("DUPLICATE_CATEGORY"));
    }

    /**
     * Получение несуществующей категории
     * возвращает 404 Not Found.
     */
    @Test
    void getNonExistingCategoryReturns404()
            throws Exception {
        mockMvc.perform(get(BASE_URL + "/999999999").header("Authorization", "Bearer " + adminToken))
               .andExpect(status().isNotFound())
               .andExpect(jsonPath("$.error").value("ENTITY_NOT_FOUND"));
    }

    /**
     * Пустое название категории не проходит валидацию.
     */
    @Test
    void createCategoryBlankNameReturns400()
            throws Exception {
        CreateCategoryRequest request = new CreateCategoryRequest("");

        mockMvc.perform(post(BASE_URL).header("Authorization", "Bearer " + adminToken)
                                      .contentType(MediaType.APPLICATION_JSON)
                                      .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    /**
     * USER не может создавать категории.
     */
    @Test
    void createCategoryUserReturns403()
            throws Exception {
        CreateCategoryRequest request = new CreateCategoryRequest("Запрещенная категория-" + suffix);

        mockMvc.perform(post(BASE_URL).header("Authorization", "Bearer " + userToken)
                                      .contentType(MediaType.APPLICATION_JSON)
                                      .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isForbidden())
               .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    /**
     * Запрос без токена возвращает 401 Unauthorized.
     */
    @Test
    void createCategoryWithoutTokenReturns401()
            throws Exception {
        CreateCategoryRequest request = new CreateCategoryRequest("Категория без токена-" + suffix);

        mockMvc.perform(post(BASE_URL).contentType(MediaType.APPLICATION_JSON)
                                      .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isUnauthorized())
               .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    private Category saveCategory(String name) {
        Category category;
        if (!categoryRepository.existsByNameIgnoreCase(name)) {
            Category newCategory = new Category();
            newCategory.setName(name);
            category = categoryRepository.save(newCategory);
        } else {
            category = categoryRepository.findByNameIgnoreCase(name).get();
        }
        return category;
    }

    private Item saveItem(Category category) {
        Item item = new Item();
        item.setSku("SKU-CATEGORY-TEST-" + suffix);
        item.setName("Товар с используемой категорией");
        item.setCategory(category);
        item.setMinStock(5);
        item.setPrice(BigDecimal.valueOf(100.00));
        item.setCost(BigDecimal.valueOf(50.00));
        item.setActive(true);

        return itemRepository.save(item);
    }

    private String obtainToken(String username, String password)
            throws Exception {

        LoginRequest request = new LoginRequest(username, password);

        String response = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                                                                 .content(objectMapper.writeValueAsString(request)))
                                 .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                                 .andExpect(status().isOk())
                                 .andReturn()
                                 .getResponse()
                                 .getContentAsString();

        return objectMapper.readTree(response).get("accessToken").asText();
    }
}