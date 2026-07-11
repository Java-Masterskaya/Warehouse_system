package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.purchaseorder.CreatePurchaseOrderItemRequest;
import com.warehouse.dto.request.purchaseorder.CreatePurchaseOrderRequest;
import com.warehouse.dto.request.purchaseorder.ReceivePurchaseOrderItemRequest;
import com.warehouse.dto.request.purchaseorder.ReceivePurchaseOrderRequest;
import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.Supplier;
import com.warehouse.entity.User;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.SupplierRepository;
import com.warehouse.repository.UserRepository;
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
class PurchaseOrderControllerTest extends AbstractIntegrationTest {

    private static final String BASE_URL = "/api/purchase-orders";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminToken;
    private Supplier supplier;
    private Item item;

    @BeforeEach
    void setUp() throws Exception {
        User admin = userRepository.findByUsername("admin")
                .map(user -> {
                    user.setPassword(passwordEncoder.encode("secret"));
                    user.setRole(Role.ROLE_ADMIN);
                    user.setActive(true);
                    return userRepository.save(user);
                })
                .orElseGet(() -> {
                    User user = new User();
                    user.setUsername("admin");
                    user.setPassword(passwordEncoder.encode("secret"));
                    user.setRole(Role.ROLE_ADMIN);
                    user.setActive(true);
                    return userRepository.save(user);
                });

        adminToken = obtainToken(admin.getUsername(), "secret");

        supplier = Supplier.builder()
                .name("Test Supplier")
                .active(true)
                .build();
        supplier = supplierRepository.save(supplier);

        item = Item.builder()
                .sku("PO-SKU-" + System.nanoTime())
                .name("Test Purchase Order Item")
                .category("Test")
                .minStock(0)
                .active(true)
                .build();
        item = itemRepository.save(item);

        Stock stock = Stock.builder()
                .item(item)
                .quantity(0)
                .build();
        stockRepository.save(stock);
    }

    @Test
    void createPurchaseOrderReturns201AndDraftStatus() throws Exception {
        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest(
                supplier.getId(),
                List.of(new CreatePurchaseOrderItemRequest(item.getId(), 10))
        );

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierId").value(supplier.getId()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.items[0].itemId").value(item.getId()))
                .andExpect(jsonPath("$.items[0].orderedQty").value(10))
                .andExpect(jsonPath("$.items[0].receivedQty").value(0));
    }

    @Test
    void receiveDraftPurchaseOrderReturns409() throws Exception {
        JsonNode createdOrder = createPurchaseOrder(10);

        long purchaseOrderId = createdOrder.get("id").asLong();
        long purchaseOrderItemId =
                createdOrder.get("items").get(0).get("id").asLong();

        ReceivePurchaseOrderRequest request =
                new ReceivePurchaseOrderRequest(
                        List.of(
                                new ReceivePurchaseOrderItemRequest(
                                        purchaseOrderItemId,
                                        5
                                )
                        )
                );

        mockMvc.perform(post(BASE_URL + "/" + purchaseOrderId + "/receive")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("INVALID_PURCHASE_ORDER_STATUS"));

        assertThat(stockRepository.findByItemId(item.getId())
                .orElseThrow()
                .getQuantity())
                .isZero();
    }

    @Test
    void partialReceiptUpdatesStockAndStatus() throws Exception {
        JsonNode createdOrder = createPurchaseOrder(10);

        long purchaseOrderId = createdOrder.get("id").asLong();
        long purchaseOrderItemId =
                createdOrder.get("items").get(0).get("id").asLong();

        placePurchaseOrder(purchaseOrderId);

        ReceivePurchaseOrderRequest request =
                new ReceivePurchaseOrderRequest(
                        List.of(
                                new ReceivePurchaseOrderItemRequest(
                                        purchaseOrderItemId,
                                        4
                                )
                        )
                );

        mockMvc.perform(post(BASE_URL + "/" + purchaseOrderId + "/receive")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.items[0].receivedQty").value(4));

        Stock updatedStock =
                stockRepository.findByItemId(item.getId()).orElseThrow();

        assertThat(updatedStock.getQuantity()).isEqualTo(4);

        boolean receiveMovementExists =
                stockMovementRepository.findAll().stream()
                        .anyMatch(movement ->
                                movement.getType() == MovementType.RECEIVE
                                        && movement.getQuantity() == 4
                                        && movement.getItem().getId()
                                        .equals(item.getId()));

        assertThat(receiveMovementExists).isTrue();
    }

    @Test
    void fullReceiptUpdatesStockAndStatusToReceived() throws Exception {
        JsonNode createdOrder = createPurchaseOrder(10);

        long purchaseOrderId = createdOrder.get("id").asLong();
        long purchaseOrderItemId =
                createdOrder.get("items").get(0).get("id").asLong();

        placePurchaseOrder(purchaseOrderId);

        ReceivePurchaseOrderRequest request =
                new ReceivePurchaseOrderRequest(
                        List.of(
                                new ReceivePurchaseOrderItemRequest(
                                        purchaseOrderItemId,
                                        10
                                )
                        )
                );

        mockMvc.perform(post(BASE_URL + "/" + purchaseOrderId + "/receive")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.items[0].receivedQty").value(10));

        assertThat(stockRepository.findByItemId(item.getId())
                .orElseThrow()
                .getQuantity())
                .isEqualTo(10);
    }

    @Test
    void overReceiptReturns422AndDoesNotChangeStock() throws Exception {
        JsonNode createdOrder = createPurchaseOrder(10);

        long purchaseOrderId = createdOrder.get("id").asLong();
        long purchaseOrderItemId =
                createdOrder.get("items").get(0).get("id").asLong();

        placePurchaseOrder(purchaseOrderId);

        ReceivePurchaseOrderRequest request =
                new ReceivePurchaseOrderRequest(
                        List.of(
                                new ReceivePurchaseOrderItemRequest(
                                        purchaseOrderItemId,
                                        11
                                )
                        )
                );

        mockMvc.perform(post(BASE_URL + "/" + purchaseOrderId + "/receive")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error")
                        .value("PURCHASE_ORDER_OVER_RECEIPT"));

        assertThat(stockRepository.findByItemId(item.getId())
                .orElseThrow()
                .getQuantity())
                .isZero();
    }

    private JsonNode createPurchaseOrder(int orderedQty) throws Exception {
        CreatePurchaseOrderRequest request =
                new CreatePurchaseOrderRequest(
                        supplier.getId(),
                        List.of(
                                new CreatePurchaseOrderItemRequest(
                                        item.getId(),
                                        orderedQty
                                )
                        )
                );

        String response = mockMvc.perform(post(BASE_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response);
    }

    private void placePurchaseOrder(Long purchaseOrderId) throws Exception {
        mockMvc.perform(post(BASE_URL + "/" + purchaseOrderId + "/place")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLACED"));
    }

    private String obtainToken(String username, String password)
            throws Exception {

        LoginRequest request = new LoginRequest(username, password);

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response)
                .get("token")
                .asText();
    }
}
