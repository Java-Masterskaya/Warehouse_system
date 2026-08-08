package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.reservation.ReservationActionRequest;
import com.warehouse.dto.request.reservation.ReserveRequest;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockAlertRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StockReserveControllerTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    StockRepository stockRepository;

    @Autowired
    BatchRepository batchRepository;

    @Autowired
    StockReserveRepository reservationRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    StockAlertRepository stockAlertRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private String adminToken;
    private String userToken;

    private Item item;
    private Stock stock;
    private Category category;

    @BeforeEach
    void setUp() throws Exception {
        // Класс проверяет резервы через reservationRepository.findAll(): без очистки
        // в выборку попадают чужие резервы, и проверки расходятся («ожидали 5, получили 7»,
        // «Expecting empty»). Своих данных класс до этого вообще не изолировал.
        cleanDomainData();

        category = categoryRepository.findByNameIgnoreCase("test")
                .orElseGet(() -> categoryRepository.save(
                        Category.builder()
                                .name("test")
                                .build()
                ));

        item = new Item();
        item.setSku("SKU-" + System.currentTimeMillis());
        item.setName("Test item");
        item.setCategory(category);
        item.setMinStock(1);
        item.setActive(true);
        item.setBarcode("ITEM-TEST-RESERVE-" + System.nanoTime());

        itemRepository.save(item);

        stock = new Stock();
        stock.setItem(item);
        stock.setWarehouse(defaultWarehouse());
        stock.setQuantity(10);

        stockRepository.save(stock);

        batchRepository.save(Batch.builder()
                .item(item)
                .warehouse(defaultWarehouse())
                .quantity(10)
                .expiryDate(LocalDateTime.now().plusDays(30))
                .build());

        User admin = userRepository.findByUsername("admin").orElseThrow();

        // Пользователя именно сохраняем, а не собираем в памяти: прежний orElse(...) создавал
        // объект с фиксированным id=1, и токен выписывался несуществующей учётке. Пока testuser
        // всегда лежал в базе, ветка не срабатывала — теперь cleanDomainData() его удаляет.
        User user = userRepository.findByUsername("testuser")
                .orElseGet(() -> userRepository.save(
                        User.builder()
                            .username("testuser")
                            .password("pass@12Word")
                            .role(Role.ROLE_USER)
                            .active(true)
                            .createdAt(LocalDateTime.now())
                            .build()));

        adminToken = jwtUtil.generateToken(admin.getUsername(), admin.getId(), List.of("ROLE_ADMIN"));

        userToken = jwtUtil.generateToken(user.getUsername(), user.getId(), List.of("ROLE_USER"));
    }

    @Test
    void adminCanReserveItem() throws Exception {

        ReserveRequest request = new ReserveRequest(5, 1);

        mockMvc.perform(post(V1_API_ROOT + "/stock/{id}/reserve", item.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.itemId").value(item.getId()))
                .andExpect(jsonPath("$.quantity").value(5)).andExpect(jsonPath("$.status").value("ACTIVE"));

        Reservation reservation = reservationRepository.findAll().get(0);

        assertThat(reservation.getQuantity()).isEqualTo(5);

        // физический остаток не меняется
        assertThat(stockRepository.findById(stock.getId()).orElseThrow().getQuantity()).isEqualTo(10);
    }

    @Test
    void userCannotReserveItem() throws Exception {

        ReserveRequest request = new ReserveRequest(5, 1);

        mockMvc.perform(post(V1_API_ROOT + "/stock/{id}/reserve", item.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotReserveMoreThanAvailable() throws Exception {

        ReserveRequest request = new ReserveRequest(20, 1);

        mockMvc.perform(post(V1_API_ROOT + "/stock/{id}/reserve", item.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(reservationRepository.findAll()).isEmpty();
    }

    @Test
    void adminCanReleaseReservation() throws Exception {

        Reservation reservation = createReservation(5);

        ReservationActionRequest request = new ReservationActionRequest(reservation.getId());

        mockMvc.perform(post(V1_API_ROOT + "/stock/{id}/release", item.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));

        Reservation updated = reservationRepository.findById(reservation.getId()).orElseThrow();

        assertThat(updated.getStatus()).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    void adminCanWriteOffReservation() throws Exception {
        item.setMinStock(6);
        itemRepository.saveAndFlush(item);

        Reservation reservation = createReservation(5);

        ReservationActionRequest request = new ReservationActionRequest(reservation.getId());

        mockMvc.perform(post(V1_API_ROOT + "/stock/{id}/write-off", item.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONSUMED"));

        Stock updated = stockRepository.findById(stock.getId()).orElseThrow();
        Reservation updatedReservation = reservationRepository.findById(reservation.getId()).orElseThrow();
        Batch updatedBatch = batchRepository
                .findByItemIdAndWarehouseIdOrderByExpiryDateAsc(item.getId(), defaultWarehouse().getId())
                .getFirst();

        assertThat(updated.getQuantity()).isEqualTo(5);
        assertThat(updatedBatch.getQuantity()).isEqualTo(5);
        assertThat(updatedReservation.getStatus()).isEqualTo(ReservationStatus.CONSUMED);
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM stock_movements
                        WHERE item_id = ?
                          AND warehouse_id = ?
                          AND type = 'WRITE_OFF'
                          AND quantity = 5
                        """,
                Long.class,
                item.getId(),
                defaultWarehouse().getId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT u.username
                        FROM stock_movements sm
                        JOIN users u ON u.id = sm.user_id
                        WHERE sm.item_id = ?
                          AND sm.type = 'WRITE_OFF'
                        """,
                String.class,
                item.getId()
        )).isEqualTo("admin");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var alerts = stockAlertRepository.findByItemId(item.getId());
            assertThat(alerts).singleElement().satisfies(alert -> {
                assertThat(alert.getCurrentStock()).isEqualTo(5);
                assertThat(alert.getMinStock()).isEqualTo(6);
                assertThat(alert.getTriggeredBy()).isEqualTo("admin");
            });
        });
    }

    private Reservation createReservation(int quantity) {

        Reservation reservation = new Reservation();

        reservation.setStock(stock);
        reservation.setQuantity(quantity);
        reservation.setUser(userRepository.findByUsername("admin").orElseThrow());
        reservation.setStatus(ReservationStatus.ACTIVE);
        reservation.setExpiredAt(LocalDateTime.now().plusDays(1));

        return reservationRepository.save(reservation);
    }
}
