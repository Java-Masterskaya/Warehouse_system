package com.warehouse.security.actuator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.security.LoginRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "management.server.port=0" }
)
@ActiveProfiles("test")
class ActuatorSecurityTest extends AbstractIntegrationTest {

    // Spring Boot автоматически подставит рандомный порт основного приложения
    @LocalServerPort
    private int serverPort;

    // Spring Boot автоматически подставит рандомный порт для менеджмент-сервера
    @LocalManagementPort
    private int managementPort;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    private final String prometheus = "/actuator/prometheus";
    private final String health = "/actuator/health";
    private final String liveness = "/actuator/health/liveness";
    private final String readiness = "/actuator/health/readiness";
    private final String refresh = "/actuator/refresh";

    private String getManagementUrl(String path) {
        return "http://localhost:" + managementPort + path;
    }

    private String getServerUrl(String path) {
        return "http://localhost:" + serverPort + path;
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        this.adminToken = obtainAdminToken("admin", "secret");
    }

    private HttpEntity<Void> getHeadersWithAdminToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        return new HttpEntity<>(headers);
    }

    private HttpEntity<Void> getAnonymousHeaders() {
        return new HttpEntity<>(new HttpHeaders());
    }

    @Test
    @DisplayName("GET /actuator/prometheus на основном порту должен отдавать 404")
    void prometheusOnMainPortReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(getServerUrl(prometheus), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /actuator/prometheus анонимно на management-порту должен отдавать 200 OK")
    void prometheusAnonymousOnManagementPortReturns200() {
        ResponseEntity<String> response = restTemplate.getForEntity(getManagementUrl(prometheus), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /actuator/health анонимно отдает 200 OK, но скрывает детали")
    void healthAnonymousReturns200WithoutDetails() {
        ResponseEntity<String> response = restTemplate.getForEntity(getManagementUrl(health), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        // Проверяем, что в JSON нет внутренних компонентов (например, diskSpace, db)
        assertThat(response.getBody()).doesNotContain("\"components\"");
        assertThat(response.getBody()).doesNotContain("\"details\"");
    }

    @Test
    @DisplayName("GET /actuator/health с токеном для ADMIN отдает 200 OK и показывает детали")
    void healthAdminReturns200WithDetails() {
        ResponseEntity<String> response = restTemplate.exchange(
                getManagementUrl(health),
                HttpMethod.GET,
                getHeadersWithAdminToken(),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody()).containsAnyOf("\"components\"", "\"details\"");
    }

    @Test
    @DisplayName("GET /actuator/health/liveness доступен без авторизации")
    void livenessAnonymousReturns200() {
        ResponseEntity<String> response = restTemplate.getForEntity(getManagementUrl(liveness), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("GET /actuator/health/readiness доступен без авторизации")
    void readinessAnonymousReturns200() {
        ResponseEntity<String> response = restTemplate.getForEntity(getManagementUrl(readiness), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("POST /actuator/refresh без авторизации должен отдавать 401 Unauthorized")
    void refreshAnonymousReturns401() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                getManagementUrl(refresh), getAnonymousHeaders(), String.class
        );
        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /actuator/refresh с токеном для ADMIN должен отдавать 200 OK")
    void refreshAdminReturns200() {
        ResponseEntity<String> response = restTemplate.exchange(
                getManagementUrl(refresh),
                HttpMethod.POST,
                getHeadersWithAdminToken(),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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
