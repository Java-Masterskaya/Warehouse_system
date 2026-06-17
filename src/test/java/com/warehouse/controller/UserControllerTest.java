package com.warehouse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.user.UserCreateRequest;
import com.warehouse.dto.response.user.UserResponse;
import com.warehouse.entity.Role;
import com.warehouse.service.user.UserService;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class UserControllerTest extends AbstractIntegrationTest {

    // ==========================================
    // UNIT ТЕСТЫ С @TestConfiguration
    // ==========================================

    @WebMvcTest(UserController.class)
    class UserControllerSecurityUnitTest {

        @TestConfiguration
        static class TestConfig {
            @Bean
            @Primary
            public UserService userService() {
                return Mockito.mock(UserService.class);
            }
        }

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private UserService userService;

        @Nested
        @DisplayName("POST /api/users - Unit Security Tests")
        class CreateUserSecurityTests {

            @Test
            @WithMockUser(roles = "ADMIN")
            @DisplayName("ADMIN может создать пользователя - 201")
            void adminCanCreateUser() throws Exception {
                UserCreateRequest request = new UserCreateRequest();
                request.setUsername("newuser");
                request.setPassword("password123");
                request.setRole(Role.ROLE_USER);

                UserResponse response = new UserResponse();
                response.setId(1L);
                response.setUsername("newuser");
                response.setRole("ROLE_USER");
                response.setActive(true);
                response.setCreatedAt(LocalDateTime.now());

                when(userService.createUser(any(UserCreateRequest.class))).thenReturn(response);

                mockMvc.perform(post("/api/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.username").value("newuser"));
            }

            @Test
            @WithMockUser(roles = "USER")
            @DisplayName("USER не может создать пользователя - 403")
            void userCannotCreateUser() throws Exception {
                UserCreateRequest request = new UserCreateRequest();
                request.setUsername("newuser");
                request.setPassword("password123");
                request.setRole(Role.ROLE_USER);

                mockMvc.perform(post("/api/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isForbidden());
            }

            @Test
            @DisplayName("Без аутентификации - 401")
            void noAuthReturns401() throws Exception {
                UserCreateRequest request = new UserCreateRequest();
                request.setUsername("newuser");
                request.setPassword("password123");
                request.setRole(Role.ROLE_USER);

                mockMvc.perform(post("/api/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isUnauthorized());
            }
        }

        @Nested
        @DisplayName("GET /api/users - Unit Security Tests")
        class GetUsersSecurityTests {

            @Test
            @WithMockUser(roles = "ADMIN")
            @DisplayName("ADMIN может получить пользователей - 200")
            void adminCanGetUsers() throws Exception {
                mockMvc.perform(get("/api/users"))
                        .andExpect(status().isOk());
            }

            @Test
            @WithMockUser(roles = "USER")
            @DisplayName("USER не может получить пользователей - 403")
            void userCannotGetUsers() throws Exception {
                mockMvc.perform(get("/api/users"))
                        .andExpect(status().isForbidden());
            }

            @Test
            @DisplayName("Без аутентификации - 401")
            void noAuthReturns401() throws Exception {
                mockMvc.perform(get("/api/users"))
                        .andExpect(status().isUnauthorized());
            }
        }
    }
}