package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest extends AbstractIntegrationTest {

    private static final String BASE_URL = V1_API_ROOT + "/users";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User admin;
    private User secondAdmin;

    private String adminToken;
    private String secondAdminToken;

    @BeforeEach
    void setUp() throws Exception {
        admin = createUser("admin", "secret", Role.ROLE_ADMIN);
        secondAdmin = createUser("admin2", "secret", Role.ROLE_ADMIN);

        adminToken = obtainToken("admin", "secret");
        secondAdminToken = obtainToken("admin2", "secret");
    }

    @Test
    void selfDeactivationReturns400() throws Exception {
        mockMvc.perform(delete(BASE_URL + "/" + admin.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SELF_DEACTIVATION"));

        User updated = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(updated.isActive()).isTrue();
    }

    @Test
    void adminCanDeactivateAnotherAdmin() throws Exception {
        mockMvc.perform(delete(BASE_URL + "/" + admin.getId())
                        .header("Authorization", "Bearer " + secondAdminToken))
                .andExpect(status().isNoContent());

        User updatedAdmin = userRepository.findById(admin.getId()).orElseThrow();
        User updatedSecondAdmin = userRepository.findById(secondAdmin.getId()).orElseThrow();

        assertThat(updatedAdmin.isActive()).isFalse();
        assertThat(updatedSecondAdmin.isActive()).isTrue();
    }

    private User createUser(String username, String password, Role role) {
        return userRepository.findByUsername(username)
                .map(user -> {
                    user.setPassword(passwordEncoder.encode(password));
                    user.setRole(role);
                    user.setActive(true);
                    return userRepository.save(user);
                })
                .orElseGet(() -> {
                    User user = new User();
                    user.setUsername(username);
                    user.setPassword(passwordEncoder.encode(password));
                    user.setRole(role);
                    user.setActive(true);
                    return userRepository.save(user);
                });
    }

    private String obtainToken(String username, String password) throws Exception {
        LoginRequest request = new LoginRequest(username, password);

        String response = mockMvc.perform(post(V1_API_ROOT + "/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("accessToken").asText();
    }
}
