package com.warehouse.security.actuator;

import com.warehouse.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    @DisplayName("GET /actuator/prometheus на основном порту должен отдавать 403")
    void prometheusOnMainPortReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(getServerUrl(prometheus), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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
    @DisplayName("GET /actuator/health с ролью ADMIN отдает 200 OK и показывает детали")
    void healthAdminReturns200WithDetails() {
        // Замените "admin" и "password" на ваши тестовые креды администратора
        TestRestTemplate adminTemplate = restTemplate.withBasicAuth("admin", "secret");

        ResponseEntity<String> response = adminTemplate.getForEntity(getManagementUrl(health), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        // Actuator при show-details: when_authorized возвращает детали авторизованному ADMIN
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
    @DisplayName("POST /actuator/refresh без авторизации должен отдавать 403 Forbidden")
    void refreshAnonymousReturns401() {
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.postForEntity(getManagementUrl(refresh), entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /actuator/refresh с ролью ADMIN должен отдавать 200 OK")
    void refreshAdminReturns200() {
        TestRestTemplate adminTemplate = restTemplate.withBasicAuth("admin", "secret");
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = adminTemplate.postForEntity(getManagementUrl(refresh), entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
