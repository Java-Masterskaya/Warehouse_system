package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.TransferStockRequest;
import com.warehouse.dto.response.movement.StockTransferResponse;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.security.util.JwtUtil;
import com.warehouse.service.movement.StockMovementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused integration tests for atomic stock transfers between warehouses.
 */
@AutoConfigureMockMvc
class StockTransferControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String TRANSFER_URL = "/api/movements/transfer";
    private static final int INITIAL_SOURCE_QUANTITY = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockReserveRepository stockReserveRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private StockMovementService stockMovementService;

    private Warehouse sourceWarehouse;
    private Warehouse destinationWarehouse;
    private Item item;
    private Stock sourceStock;
    private User admin;
    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();

        sourceWarehouse = warehouseRepository.findByDefaultWarehouseTrue().orElseThrow();
        destinationWarehouse = warehouseRepository.saveAndFlush(Warehouse.builder()
                .name("DOM4 Destination " + suffix)
                .defaultWarehouse(false)
                .build());

        item = itemRepository.saveAndFlush(Item.builder()
                .sku("DOM4-" + suffix)
                .name("DOM4 transfer item")
                .category("DOM4")
                .minStock(1)
                .price(BigDecimal.TEN)
                .cost(BigDecimal.ONE)
                .active(true)
                .build());

        sourceStock = stockRepository.saveAndFlush(Stock.builder()
                .item(item)
                .warehouse(sourceWarehouse)
                .quantity(INITIAL_SOURCE_QUANTITY)
                .build());

        admin = createUser("dom4-admin-" + suffix, Role.ROLE_ADMIN);
        User regularUser = createUser("dom4-user-" + suffix, Role.ROLE_USER);
        adminToken = jwtUtil.generateToken(admin.getUsername(), admin.getId(), List.of(Role.ROLE_ADMIN.name()));
        userToken = jwtUtil.generateToken(
                regularUser.getUsername(),
                regularUser.getId(),
                List.of(Role.ROLE_USER.name())
        );
    }

    @Test
    void adminTransferCreatesDestinationStockAndTwoLinkedMovements() throws Exception {
        int quantity = 4;
        long totalBefore = stockRepository.findTotalQuantityByItemId(item.getId());

        JsonNode response = objectMapper.readTree(mockMvc.perform(post(TRANSFER_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
                                sourceWarehouse.getId(),
                                destinationWarehouse.getId(),
                                quantity
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value(item.getId()))
                .andExpect(jsonPath("$.fromWarehouseId").value(sourceWarehouse.getId()))
                .andExpect(jsonPath("$.toWarehouseId").value(destinationWarehouse.getId()))
                .andExpect(jsonPath("$.quantity").value(quantity))
                .andExpect(jsonPath("$.fromStockAfter").value(INITIAL_SOURCE_QUANTITY - quantity))
                .andExpect(jsonPath("$.toStockAfter").value(quantity))
                .andExpect(jsonPath("$.transferId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString());

        UUID transferId = UUID.fromString(response.get("transferId").asText());
        Stock updatedSource = findStock(sourceWarehouse);
        Stock createdDestination = findStock(destinationWarehouse);

        assertThat(updatedSource.getQuantity()).isEqualTo(INITIAL_SOURCE_QUANTITY - quantity);
        assertThat(createdDestination.getQuantity()).isEqualTo(quantity);
        assertThat(stockRepository.findTotalQuantityByItemId(item.getId())).isEqualTo(totalBefore);

        List<MovementRow> movements = findMovements(transferId);
        assertThat(movements).hasSize(2);
        assertThat(movements)
                .extracting(MovementRow::warehouseId, MovementRow::type, MovementRow::quantity)
                .containsExactlyInAnyOrder(
                        tuple(sourceWarehouse.getId(), MovementType.TRANSFER_OUT, quantity),
                        tuple(destinationWarehouse.getId(), MovementType.TRANSFER_IN, quantity)
            );
        assertThat(movements)
                .extracting(MovementRow::transferId)
                .containsOnly(transferId);
        assertThat(movements)
                .extracting(MovementRow::id)
                .containsExactlyInAnyOrder(
                        response.get("outMovementId").asLong(),
                        response.get("inMovementId").asLong()
            );
        assertThat(countMovements()).isEqualTo(2);

        mockMvc.perform(get("/api/movements/{itemId}/history", item.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[*].type")
                        .value(containsInAnyOrder("TRANSFER_OUT", "TRANSFER_IN")));
    }

    @Test
    void insufficientSourceReturns422AndRollsBackStocksAndMovements() throws Exception {
        long movementsBefore = countMovements();
        long totalBefore = stockRepository.findTotalQuantityByItemId(item.getId());

        mockMvc.perform(post(TRANSFER_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
                                sourceWarehouse.getId(),
                                destinationWarehouse.getId(),
                                INITIAL_SOURCE_QUANTITY + 1
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"));

        assertThat(findStock(sourceWarehouse).getQuantity()).isEqualTo(INITIAL_SOURCE_QUANTITY);
        assertThat(stockRepository.findByItemIdAndWarehouseId(
                item.getId(), destinationWarehouse.getId())).isEmpty();
        assertThat(stockRepository.findTotalQuantityByItemId(item.getId())).isEqualTo(totalBefore);
        assertThat(countMovements()).isEqualTo(movementsBefore);
    }

    @Test
    void userTransferReturns403WithoutChangingStock() throws Exception {
        mockMvc.perform(post(TRANSFER_URL)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
                                sourceWarehouse.getId(),
                                destinationWarehouse.getId(),
                                1
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        assertThat(findStock(sourceWarehouse).getQuantity()).isEqualTo(INITIAL_SOURCE_QUANTITY);
        assertThat(stockRepository.findByItemIdAndWarehouseId(
                item.getId(), destinationWarehouse.getId())).isEmpty();
        assertThat(countMovements()).isZero();
    }

    @Test
    void sameWarehouseReturns400WithoutChangingStock() throws Exception {
        mockMvc.perform(post(TRANSFER_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
                                sourceWarehouse.getId(),
                                sourceWarehouse.getId(),
                                1
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_MOVEMENT_REQUEST"));

        assertThat(findStock(sourceWarehouse).getQuantity()).isEqualTo(INITIAL_SOURCE_QUANTITY);
        assertThat(countMovements()).isZero();
    }

    @Test
    void activeSourceReservationReducesTransferableQuantity() throws Exception {
        Stock destinationStock = createDestinationStock(2);
        stockReserveRepository.saveAndFlush(Reservation.builder()
                .stock(sourceStock)
                .user(admin)
                .quantity(7)
                .status(ReservationStatus.ACTIVE)
                .expiredAt(LocalDateTime.now().plusDays(1))
                .build());
        long movementsBefore = countMovements();
        long totalBefore = stockRepository.findTotalQuantityByItemId(item.getId());

        mockMvc.perform(post(TRANSFER_URL)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
                                sourceWarehouse.getId(),
                                destinationWarehouse.getId(),
                                4
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("available 3")));

        assertThat(findStock(sourceWarehouse).getQuantity()).isEqualTo(INITIAL_SOURCE_QUANTITY);
        assertThat(findStock(destinationWarehouse).getQuantity()).isEqualTo(destinationStock.getQuantity());
        assertThat(stockRepository.findTotalQuantityByItemId(item.getId())).isEqualTo(totalBefore);
        assertThat(countMovements()).isEqualTo(movementsBefore);
    }

    @Test
    void concurrentTransfersCannotOversellSource() throws Exception {
        createDestinationStock(0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Callable<StockTransferResponse> task = transferTask(
                    ready,
                    start,
                    sourceWarehouse.getId(),
                    destinationWarehouse.getId(),
                    7
            );
            List<Future<StockTransferResponse>> futures = List.of(
                    executor.submit(task),
                    executor.submit(task)
            );

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successful = 0;
            int rejected = 0;
            for (Future<StockTransferResponse> future : futures) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    successful++;
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof InsufficientStockException) {
                        rejected++;
                    } else {
                        throw ex;
                    }
                }
            }

            assertThat(successful).isEqualTo(1);
            assertThat(rejected).isEqualTo(1);
            assertThat(findStock(sourceWarehouse).getQuantity()).isEqualTo(3);
            assertThat(findStock(destinationWarehouse).getQuantity()).isEqualTo(7);
            assertThat(stockRepository.findTotalQuantityByItemId(item.getId())).isEqualTo(10);
            assertThat(countMovements()).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void oppositeTransfersLockWarehousesInSameOrder() throws Exception {
        createDestinationStock(INITIAL_SOURCE_QUANTITY);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<StockTransferResponse> forward = executor.submit(transferTask(
                    ready,
                    start,
                    sourceWarehouse.getId(),
                    destinationWarehouse.getId(),
                    4
            ));
            Future<StockTransferResponse> backward = executor.submit(transferTask(
                    ready,
                    start,
                    destinationWarehouse.getId(),
                    sourceWarehouse.getId(),
                    4
            ));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(forward.get(10, TimeUnit.SECONDS).transferId()).isNotNull();
            assertThat(backward.get(10, TimeUnit.SECONDS).transferId()).isNotNull();

            assertThat(findStock(sourceWarehouse).getQuantity()).isEqualTo(INITIAL_SOURCE_QUANTITY);
            assertThat(findStock(destinationWarehouse).getQuantity()).isEqualTo(INITIAL_SOURCE_QUANTITY);
            assertThat(stockRepository.findTotalQuantityByItemId(item.getId())).isEqualTo(20);
            assertThat(countMovements()).isEqualTo(4);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private TransferStockRequest request(Long fromWarehouseId, Long toWarehouseId, int quantity) {
        return new TransferStockRequest(item.getId(), fromWarehouseId, toWarehouseId, quantity);
    }

    private Callable<StockTransferResponse> transferTask(
            CountDownLatch ready,
            CountDownLatch start,
            Long fromWarehouseId,
            Long toWarehouseId,
            int quantity
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start transfer");
            }
            return stockMovementService.transfer(
                    request(fromWarehouseId, toWarehouseId, quantity),
                    new UserContext(admin.getId(), admin.getUsername())
            );
        };
    }

    private User createUser(String username, Role role) {
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .password("unused-password")
                .role(role)
                .active(true)
                .build());
    }

    private Stock createDestinationStock(int quantity) {
        return stockRepository.saveAndFlush(Stock.builder()
                .item(item)
                .warehouse(destinationWarehouse)
                .quantity(quantity)
                .build());
    }

    private Stock findStock(Warehouse warehouse) {
        return stockRepository.findByItemIdAndWarehouseId(item.getId(), warehouse.getId()).orElseThrow();
    }

    private long countMovements() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE item_id = ?",
                Long.class,
                item.getId()
        );
    }

    private List<MovementRow> findMovements(UUID transferId) {
        return jdbcTemplate.query(
                """
                        SELECT id, warehouse_id, type, quantity, transfer_id
                        FROM stock_movements
                        WHERE transfer_id = ?
                        """,
                (resultSet, rowNumber) -> new MovementRow(
                        resultSet.getLong("id"),
                        resultSet.getLong("warehouse_id"),
                        MovementType.valueOf(resultSet.getString("type")),
                        resultSet.getInt("quantity"),
                        resultSet.getObject("transfer_id", UUID.class)
                ),
                transferId
        );
    }

    private record MovementRow(
            long id,
            long warehouseId,
            MovementType type,
            int quantity,
            UUID transferId
    ) {
    }
}
