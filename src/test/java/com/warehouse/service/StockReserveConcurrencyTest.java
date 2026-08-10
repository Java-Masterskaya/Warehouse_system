package com.warehouse.service;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.reservation.ReserveRequest;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.ReservationStatus;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.User;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.reservation.StockReserveService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class StockReserveConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    StockRepository stockRepository;
    @Autowired
    BatchRepository batchRepository;
    @Autowired
    StockReserveRepository reserveRepository;
    @Autowired
    UserRepository         userRepository;
    @Autowired
    ItemRepository         itemRepository;
    @Autowired
    StockReserveService    stockReserveService;
    @Autowired
    CategoryRepository     categoryRepository;

    @Test
    void shouldNotAllowOverReservation() throws Exception {
        String categoryName = "Category";
        Category category;
        if (!categoryRepository.existsByNameIgnoreCase(categoryName)) {
            Category newCategory = new Category();
            newCategory.setName(categoryName);
            category = categoryRepository.save(newCategory);
        } else {
            category = categoryRepository.findByNameIgnoreCase(categoryName).get();
        }

        // SKU уникален на прогон: колонка под уникальным индексом, а при переиспользовании
        // контейнеров строки переживают перезапуск. Суффикс в barcode — на будущее.
        String itemSuffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        Item item = itemRepository.save(
                Item.builder().sku("SKU-RESERVE-" + itemSuffix).name("name").category(category)
                    .minStock(0).active(true)
                    .barcode("ITEM-TEST-RESERVECONC-" + itemSuffix).build());
        Stock stock = stockRepository.save(Stock.builder()
                .item(item)
                .warehouse(defaultWarehouse())
                .quantity(10)
                .build());
        batchRepository.saveAndFlush(Batch.builder()
                .item(item)
                .warehouse(defaultWarehouse())
                .quantity(10)
                .expiryDate(LocalDateTime.now().plusDays(30))
                .build());
        // Имя уникально на прогон: строка создаётся безусловно, а users между прогонами
        // не чистятся — при переиспользовании контейнеров фиксированное имя даёт duplicate key.
        User user = userRepository.save(
                User.builder().username("reserve-concurrency-" + System.nanoTime())
                    .password("sOme1@@@").role(Role.ROLE_ADMIN).build());
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
        long reserved =
                reserveRepository.findActiveReserveSumByStock(stock, ReservationStatus.ACTIVE, LocalDateTime.now());
        assertEquals(7, reserved);
    }
}
