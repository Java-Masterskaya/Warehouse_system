package com.warehouse.security.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.request.user.UserCreateRequest;
import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционный тест для проверки различий в детализации ошибок между ADMIN и USER ролями.
 * Тестирует, что полный стек-трейс (через детализированное сообщение об ошибке) виден только админу.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorResponseRoleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = obtainToken("admin", "secret");

        User testUser = userRepository.findByUsername("testuser").orElseGet(() -> {
            User user = new User();
            user.setUsername("testuser");
            user.setPassword("password");
            user.setRole(Role.ROLE_USER);
            user.setActive(true);
            return userRepository.save(user);
        });

        userToken = jwtUtil.generateToken(
                testUser.getUsername(), 
                testUser.getId(), 
                List.of("ROLE_USER")
        );
    }

    /**
     * ADMIN видит полное детализированное сообщение об ошибке при EntityNotFoundException.
     */
    @Test
    void adminSeesFullErrorMessageForEntityNotFound() throws Exception {
        Long nonExistentId = 999999L;

        mockMvc.perform(get("/api/items/{itemId}", nonExistentId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ENTITY_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Товар не найден"));
    }

    /**
     * USER видит обобщенное сообщение "Resource not found" вместо полного стек-трейса при EntityNotFoundException.
     */
    @Test
    void userSeesGenericErrorMessageForEntityNotFound() throws Exception {
        Long nonExistentId = 999999L;

        mockMvc.perform(get("/api/items/{itemId}", nonExistentId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ENTITY_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }


    /**
     * USER получает обобщенное сообщение при попытке доступа к админской функции.
     */
    @Test
    void userSeesGenericAccessDeniedMessage() throws Exception {
        String username = "testuser_" + System.currentTimeMillis();
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setRole(Role.ROLE_USER);

        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    private String obtainToken(String username, String password) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()).get("accessToken").asText();
    }
}
