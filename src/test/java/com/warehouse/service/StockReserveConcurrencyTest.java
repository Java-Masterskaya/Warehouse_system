package com.warehouse.service;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.reservation.ReserveRequest;
import com.warehouse.entity.Item;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.reservation.StockReserveService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class StockReserveConcurrencyTest extends AbstractIntegrationTest {
    @Autowired
    StockReserveService service;
    @Autowired
    StockRepository stockRepository;
    @Autowired
    StockReserveRepository reserveRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ItemRepository itemRepository;
    @Autowired
    StockReserveService stockReserveService;

    @Test
    void shouldNotAllowOverReservation() throws Exception {
        Item item = itemRepository.save(
                Item.builder().sku("12345676").name("name").category("category").minStock(0).active(true).build());
        Stock stock = stockRepository.save(Stock.builder().item(item).quantity(10).build());
        User user = userRepository.save(
                User.builder().username("name").password("sOme1@@@").role(Role.ROLE_ADMIN).build());
        ReserveRequest request = new ReserveRequest(7, 1);
        UserContext ctx = new UserContext(user.getId(), user.getUsername());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<Void> task = () -> {
            stockReserveService.reserve(stock.getItem().getId(), request, ctx);
            return null;
        };
        List<Future<Void>> futures = executor.invokeAll(List.of(task, task));
        long successfulReservations = futures.stream().filter(future -> {
            try {
                future.get();
                return true;
            } catch (Exception e) {
                e.getCause().printStackTrace();
                return false;
            }
        }).count();
        assertEquals(1, successfulReservations);
        long reserved = reserveRepository.findActiveReserveSumByStock(stock, ReservationStatus.ACTIVE,
                LocalDateTime.now());
        assertEquals(7, reserved);
    }
}
