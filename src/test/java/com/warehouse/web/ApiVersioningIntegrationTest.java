package com.warehouse.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiVersioningIntegrationTest extends AbstractIntegrationTest {

    private static final String DEPRECATION_HEADER = "Deprecation";
    private static final String SUNSET_HEADER = "Sunset";
    private static final String SUNSET_VALUE = "Thu, 05 Aug 2027 00:00:00 GMT";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    void everyLegacyControllerPathHasCanonicalV1Mapping() {
        Set<String> controllerPaths = controllerPaths();
        Set<String> legacyPaths = new TreeSet<>();
        Set<String> versionedPaths = new TreeSet<>();

        for (String path : controllerPaths) {
            if (ApiPaths.isLegacyPath(path)) {
                legacyPaths.add(path);
            } else if (path.startsWith(ApiPaths.V1_API_ROOT + "/")) {
                versionedPaths.add(path);
            }
        }

        assertThat(legacyPaths).isNotEmpty();
        assertThat(versionedPaths).hasSameSizeAs(legacyPaths);
        assertThat(legacyPaths).allSatisfy(legacyPath ->
                assertThat(versionedPaths).contains(versionedPathFor(legacyPath))
        );
    }

    @Test
    void canonicalV1PathsUseUpdatedSecurityRulesWithoutDeprecationHeaders() throws Exception {
        mockMvc.perform(get(ApiPaths.V1_API_ROOT + "/items"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(DEPRECATION_HEADER))
                .andExpect(header().doesNotExist(SUNSET_HEADER));

        String token = tokenForActiveUser();
        mockMvc.perform(get(ApiPaths.V1_API_ROOT + "/items")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(DEPRECATION_HEADER))
                .andExpect(header().doesNotExist(SUNSET_HEADER));
    }

    @Test
    void legacyPathsRemainAvailableAndAdvertiseDeprecationEvenOnSecurityErrors() throws Exception {
        mockMvc.perform(get(ApiPaths.LEGACY_API_ROOT + "/items"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(DEPRECATION_HEADER, "true"))
                .andExpect(header().string(SUNSET_HEADER, SUNSET_VALUE));

        mockMvc.perform(post(ApiPaths.LEGACY_API_ROOT + "/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(DEPRECATION_HEADER, "true"))
                .andExpect(header().string(SUNSET_HEADER, SUNSET_VALUE));
    }

    @Test
    void swaggerPublishesOnlyCanonicalV1Paths() throws Exception {
        String response = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(DEPRECATION_HEADER))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode paths = objectMapper.readTree(response).path("paths");
        Set<String> publishedPaths = new TreeSet<>();
        paths.fieldNames().forEachRemaining(publishedPaths::add);

        assertThat(publishedPaths)
                .isNotEmpty()
                .allMatch(path -> path.startsWith(ApiPaths.V1_API_ROOT + "/"))
                .contains(
                        ApiPaths.V1_API_ROOT + "/auth/login",
                        ApiPaths.V1_API_ROOT + "/items",
                        ApiPaths.V1_BACKFILL_ROOT + "/barcode/status"
            );
    }

    private Set<String> controllerPaths() {
        Set<String> paths = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : requestMappingHandlerMapping.getHandlerMethods().entrySet()) {
            if (isApplicationController(entry.getValue())) {
                paths.addAll(entry.getKey().getPatternValues());
            }
        }
        return paths;
    }

    private boolean isApplicationController(HandlerMethod handlerMethod) {
        String typeName = handlerMethod.getBeanType().getName();
        return typeName.startsWith("com.warehouse.controller.")
                || typeName.startsWith("com.warehouse.security.controller.");
    }

    private String versionedPathFor(String legacyPath) {
        if (legacyPath.startsWith(ApiPaths.LEGACY_API_ROOT + "/")) {
            return ApiPaths.V1_API_ROOT + legacyPath.substring(ApiPaths.LEGACY_API_ROOT.length());
        }
        return ApiPaths.V1_API_ROOT + legacyPath;
    }

    private String tokenForActiveUser() {
        User user = new User();
        user.setUsername("api_v1_" + UUID.randomUUID());
        user.setPassword("not-used");
        user.setRole(Role.ROLE_USER);
        user.setActive(true);
        user = userRepository.save(user);
        return jwtUtil.generateToken(user.getUsername(), user.getId(), List.of("ROLE_USER"));
    }
}
