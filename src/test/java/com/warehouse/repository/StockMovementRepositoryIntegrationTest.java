package com.warehouse.repository;

import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Интеграционные тесты для репозитория StockMovement.
 * Использует PostgreSQL через Testcontainers.
 */
@SpringBootTest
@Testcontainers
class StockMovementRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("unused")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * Тест: Сохранение нового движения товара в БД.
     */
    @Test
    void saveStockMovementShouldPersistToDatabase() {
        // Создаём тестовые данные
        User user = User.builder()
            .username("testuser1")
            .password("password")
            .role(com.warehouse.entity.Role.ROLE_USER)
            .build();
        userRepository.save(user);

        Item item = Item.builder()
            .sku("SKU001")
            .name("Тестовый товар")
            .category("Категория")
            .minStock(5)
            .build();
        itemRepository.save(item);

        Stock stock = Stock.builder()
            .item(item)
            .quantity(100)
            .build();
        stockRepository.save(stock);

        // Сохраняем движение
        StockMovement movement = StockMovement.builder()
            .item(item)
            .user(user)
            .type(MovementType.RECEIVE)
            .quantity(10)
            .build();

        StockMovement saved = stockMovementRepository.save(movement);

        assertNotNull(saved.getId());
        assertEquals(MovementType.RECEIVE, saved.getType());
        assertEquals(10, saved.getQuantity());
    }

    /**
     * Тест: Поиск движения товара по ID.
     */
    @Test
    void findByIdShouldReturnStockMovement() {
        // Создаём тестовые данные
        User user = User.builder()
            .username("testuser2")
            .password("password")
            .role(com.warehouse.entity.Role.ROLE_USER)
            .build();
        userRepository.save(user);

        Item item = Item.builder()
            .sku("SKU002")
            .name("Тестовый товар 2")
            .category("Категория")
            .minStock(5)
            .build();
        itemRepository.save(item);

        Stock stock = Stock.builder()
            .item(item)
            .quantity(100)
            .build();
        stockRepository.save(stock);

        // Создаём и сохраняем движение
        StockMovement movement = StockMovement.builder()
            .item(item)
            .user(user)
            .type(MovementType.RECEIVE)
            .quantity(100)
            .build();
        StockMovement saved = stockMovementRepository.save(movement);

        // Проверяем поиск по ID
        Optional<StockMovement> result = stockMovementRepository.findById(saved.getId());

        assertTrue(result.isPresent());
        assertEquals(MovementType.RECEIVE, result.get().getType());
        assertEquals(100, result.get().getQuantity());
    }

    /**
     * Тест: Сохранение движения и обновление остатка.
     */
    @Test
    void saveWithStockUpdateShouldPersistBoth() {
        // Создаём тестовые данные
        User user = User.builder()
            .username("testuser3")
            .password("password")
            .role(com.warehouse.entity.Role.ROLE_USER)
            .build();
        userRepository.save(user);

        Item item = Item.builder()
            .sku("SKU003")
            .name("Тестовый товар 3")
            .category("Категория")
            .minStock(5)
            .build();
        itemRepository.save(item);

        Stock stock = Stock.builder()
            .item(item)
            .quantity(100)
            .build();
        Stock savedStock = stockRepository.save(stock);

        // Создаём и сохраняем движение
        StockMovement movement = StockMovement.builder()
            .item(item)
            .user(user)
            .type(MovementType.RECEIVE)
            .quantity(50)
            .build();
        StockMovement saved = stockMovementRepository.save(movement);

        // Обновляем остаток
        savedStock.setQuantity(savedStock.getQuantity() + 50);
        stockRepository.save(savedStock);

        assertNotNull(saved.getId());
        assertEquals(50, saved.getQuantity());

        Optional<Stock> updatedStock = stockRepository.findByItemId(item.getId());
        assertTrue(updatedStock.isPresent());
        assertEquals(150, updatedStock.get().getQuantity());
    }
}
