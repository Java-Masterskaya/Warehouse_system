package com.warehouse.security.actuator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.security.LoginRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorSecurityTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String PROMETHEUS = "/actuator/prometheus";
    private static final String HEALTH = "/actuator/health";
    private static final String LIVENESS = "/actuator/health/liveness";
    private static final String READINESS = "/actuator/health/readiness";
    private static final String REFRESH = "/actuator/refresh";

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        String token = obtainAdminToken("admin", "secret");
        this.adminToken = "Bearer " + token;
    }

    @Test
    @DisplayName("GET /actuator/prometheus анонимно -> должен отдавать 200 OK")
    void prometheusAnonymousOnManagementPortReturns200() throws Exception {
        mockMvc.perform(get(PROMETHEUS))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /actuator/health анонимно -> отдает 200 OK, но скрывает детали")
    void healthAnonymousReturns200WithoutDetails() throws Exception {
        mockMvc.perform(get(HEALTH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"UP\"")))
                .andExpect(content().string(not(containsString("\"components\""))))
                .andExpect(content().string(not(containsString("\"details\""))));
    }

    @Test
    @DisplayName("GET /actuator/health с токеном для ADMIN -> отдает 200 OK и показывает детали")
    void healthAdminReturns200WithDetails() throws Exception {
        mockMvc.perform(get(HEALTH)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"UP\"")))
                .andExpect(content().string(containsString("\"details\"")));
    }

    @Test
    @DisplayName("GET /actuator/health/liveness анонимно -> 200 OK")
    void livenessAnonymousReturns200() throws Exception {
        mockMvc.perform(get(LIVENESS))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"UP\"")));
    }

    @Test
    @DisplayName("GET /actuator/health/readiness анонимно -> 200 OK")
    void readinessAnonymousReturns200() throws Exception {
        mockMvc.perform(get(READINESS))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"UP\"")));
    }

    @Test
    @DisplayName("POST /actuator/refresh без авторизации -> должен отдавать 401 Unauthorized")
    void refreshAnonymousReturns401() throws Exception {
        mockMvc.perform(post(REFRESH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /actuator/refresh с токеном для ADMIN -> должен отдавать 200 OK")
    void refreshAdminReturns200() throws Exception {
        mockMvc.perform(post(REFRESH)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
    }

    private String obtainAdminToken(String username, String password) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()).get("accessToken").asText();
    }
}
