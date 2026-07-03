package com.warehouse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.movement.StocktakeRequest;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class InventoryControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ItemRepository itemRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String adminToken;
    private String userToken;
    private Long testItemId;

    @BeforeEach
    void setUp() throws Exception {
        Item item = new Item();
        item.setSku("SKU-INV-" + System.currentTimeMillis());
        item.setName("Test");
        item.setCategory("Cat");
        item.setMinStock(5);
        item.setActive(true);
        item = itemRepository.save(item);
        testItemId = item.getId();

        Stock stock = new Stock();
        stock.setItem(item);
        stock.setQuantity(10);
        stockRepository.save(stock);

        User admin = userRepository.findByUsername("admin").orElseGet(() -> {
            User u = new User();
            u.setUsername("admin");
            u.setPassword(passwordEncoder.encode("secret"));
            u.setRole(com.warehouse.entity.Role.ROLE_ADMIN);
            u.setActive(true);
            return userRepository.save(u);
        });

        User testUser = userRepository.findByUsername("testuser").orElseGet(() -> {
            User u = new User();
            u.setUsername("testuser");
            u.setPassword(passwordEncoder.encode("password"));
            u.setRole(com.warehouse.entity.Role.ROLE_USER);
            u.setActive(true);
            return userRepository.save(u);
        });

        adminToken = obtainToken("admin", "secret");
        userToken = jwtUtil.generateToken(testUser.getUsername(), testUser.getId(), List.of(testUser.getRole().name()));
    }

    /**
     * ADMIN проводит инвентаризацию: фактический остаток (7) меньше учётного (10).
     * Создаётся движение ADJUSTMENT на -3, остаток обновляется до 7.
     */
    @Test
    void adminStocktakeDecreasesStock() throws Exception {
        StocktakeRequest req = new StocktakeRequest(testItemId, 7);

        mockMvc.perform(post("/api/inventory/stocktake")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.quantity").value(-3))
                .andExpect(jsonPath("$.stockAfter").value(7));

        assertThat(stockRepository.findByItemId(testItemId).orElseThrow().getQuantity()).isEqualTo(7);
    }

    /**
     * USER токен не может проводить инвентаризацию,
     * возвращает статус 403 Forbidden.
     */
    @Test
    void userCannotStocktakeReturns403() throws Exception {
        StocktakeRequest req = new StocktakeRequest(testItemId, 7);

        mockMvc.perform(post("/api/inventory/stocktake")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    /**
     * Запрос без токена не может проводить инвентаризацию,
     * возвращает статус 401 Unauthorized.
     */
    @Test
    void noTokenCannotStocktakeReturns401() throws Exception {
        StocktakeRequest req = new StocktakeRequest(testItemId, 7);

        mockMvc.perform(post("/api/inventory/stocktake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    private String obtainToken(String username, String password) throws Exception {
        com.warehouse.dto.request.security.LoginRequest req = new com.warehouse.dto.request.security.LoginRequest(username, password);
        String resp = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("token").asText();
    }
}