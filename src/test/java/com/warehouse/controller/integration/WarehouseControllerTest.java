package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.warehouse.CreateWarehouseRequest;
import com.warehouse.entity.Warehouse;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.security.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class WarehouseControllerTest extends AbstractIntegrationTest {

    private static final String BASE_URL = V1_API_ROOT + "/warehouses";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private WarehouseRepository warehouseRepository;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtUtil.generateToken("warehouse-api-admin", -100L, List.of("ROLE_ADMIN"));
        userToken = jwtUtil.generateToken("warehouse-api-user", -101L, List.of("ROLE_USER"));
    }

    @Test
    void adminCreatesWarehouseAndNameIsTrimmed() throws Exception {
        String normalizedName = uniqueName("API Warehouse");
        CreateWarehouseRequest request = new CreateWarehouseRequest("  " + normalizedName + "  ");

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(normalizedName))
                .andExpect(jsonPath("$.isDefault").value(false));

        Warehouse saved = warehouseRepository.findAll().stream()
                .filter(warehouse -> warehouse.getName().equals(normalizedName))
                .findFirst()
                .orElseThrow();

        assertThat(saved.isDefaultWarehouse()).isFalse();
    }

    @Test
    void duplicateWarehouseNameReturns409() throws Exception {
        String name = uniqueName("Duplicate Warehouse");

        createWarehouse(name, adminToken).andExpect(status().isCreated());

        createWarehouse(name.toUpperCase(), adminToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_WAREHOUSE_NAME"));
    }

    @Test
    void userCannotCreateWarehouse() throws Exception {
        createWarehouse(uniqueName("Forbidden Warehouse"), userToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    void adminCanListWarehousesAndDefaultWarehouseIsMarked() throws Exception {
        Warehouse defaultWarehouse = warehouseRepository.findByDefaultWarehouseTrue().orElseThrow();

        mockMvc.perform(get(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.isDefault == true)].id", hasItem(defaultWarehouse.getId().intValue())))
                .andExpect(jsonPath("$[?(@.isDefault == true)].name", hasItem(defaultWarehouse.getName())));
    }

    @Test
    void userCanListWarehousesAndDefaultWarehouseIsMarked() throws Exception {
        Warehouse defaultWarehouse = warehouseRepository.findByDefaultWarehouseTrue().orElseThrow();

        mockMvc.perform(get(BASE_URL)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.isDefault == true)].id", hasItem(defaultWarehouse.getId().intValue())))
                .andExpect(jsonPath("$[?(@.isDefault == true)].name", hasItem(defaultWarehouse.getName())));
    }

    private ResultActions createWarehouse(String name, String token) throws Exception {
        CreateWarehouseRequest request = new CreateWarehouseRequest(name);
        return mockMvc.perform(post(BASE_URL)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
