package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.reservation.ReservationActionRequest;
import com.warehouse.dto.request.reservation.ReserveRequest;
import com.warehouse.entity.Item;
import com.warehouse.entity.Reservation;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    StockReserveRepository reservationRepository;

    @Autowired
    UserRepository userRepository;

    private String adminToken;
    private String userToken;

    private Item item;
    private Stock stock;

    @BeforeEach
    void setUp() throws Exception {

        item = new Item();
        item.setSku("SKU-" + System.currentTimeMillis());
        item.setName("Test item");
        item.setCategory("test");
        item.setMinStock(1);
        item.setActive(true);

        itemRepository.save(item);

        stock = new Stock();
        stock.setItem(item);
        stock.setWarehouse(defaultWarehouse());
        stock.setQuantity(10);

        stockRepository.save(stock);

        User admin = userRepository.findByUsername("admin").orElseThrow();

        User user = userRepository.findByUsername("testuser")
                .orElse(new User(1L, "testuser", "pass@12Word", Role.ROLE_USER, true, LocalDateTime.now()));

        adminToken = jwtUtil.generateToken(admin.getUsername(), admin.getId(), List.of("ROLE_ADMIN"));

        userToken = jwtUtil.generateToken(user.getUsername(), user.getId(), List.of("ROLE_USER"));
    }

    @Test
    void adminCanReserveItem() throws Exception {

        ReserveRequest request = new ReserveRequest(5, 1);

        mockMvc.perform(post("/api/stock/{id}/reserve", item.getId()).header("Authorization", "Bearer " + adminToken)
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

        mockMvc.perform(post("/api/stock/{id}/reserve", item.getId()).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotReserveMoreThanAvailable() throws Exception {

        ReserveRequest request = new ReserveRequest(20, 1);

        mockMvc.perform(post("/api/stock/{id}/reserve", item.getId()).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(reservationRepository.findAll()).isEmpty();
    }

    @Test
    void adminCanReleaseReservation() throws Exception {

        Reservation reservation = createReservation(5);

        ReservationActionRequest request = new ReservationActionRequest(reservation.getId());

        mockMvc.perform(post("/api/stock/{id}/release", item.getId()).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));

        Reservation updated = reservationRepository.findById(reservation.getId()).orElseThrow();

        assertThat(updated.getStatus()).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    void adminCanWriteOffReservation() throws Exception {

        Reservation reservation = createReservation(5);

        ReservationActionRequest request = new ReservationActionRequest(reservation.getId());

        mockMvc.perform(post("/api/stock/{id}/write-off", item.getId()).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONSUMED"));

        Stock updated = stockRepository.findById(stock.getId()).orElseThrow();

        assertThat(updated.getQuantity()).isEqualTo(5);
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
