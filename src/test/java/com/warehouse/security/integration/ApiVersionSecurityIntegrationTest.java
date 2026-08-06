package com.warehouse.security.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.web.ApiPaths;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that transitional and versioned authentication routes remain public.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiVersionSecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest(name = "{0} is public")
    @ValueSource(strings = {
        ApiPaths.LEGACY_API_ROOT + "/auth/login",
        ApiPaths.LEGACY_API_ROOT + "/auth/refresh",
        ApiPaths.LEGACY_API_ROOT + "/auth/logout",
        ApiPaths.V1_API_ROOT + "/auth/login",
        ApiPaths.V1_API_ROOT + "/auth/refresh",
        ApiPaths.V1_API_ROOT + "/auth/logout"
    })
    void authenticationRoutesReachValidationWithoutAuthorization(String path) throws Exception {
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
