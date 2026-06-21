package com.warehouse.service.stock;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Интеграционные тесты для StockServiceImpl с реальной базой данных через Testcontainers.
 * Проверяют: успешное пополнение, списание, обработку отсутствующих остатков.
 * 
 * <p>Использует реальную базу данных PostgreSQL через Testcontainers.
 * Тестирует полный жизненный цикл операций со складом.
 */
@SpringBootTest
class StockServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private ItemRepository itemRepository;

    private Item testItem;

    /**
     * Очищает тестовые данные после каждого теста.
     * Удаляет сначала остаток (Stock), затем товар (Item) из-за внешнего ключа.
     */
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // Очищаем тестовые данные - сначала Stock, потом Item из-за foreign key
        if (testItem != null && testItem.getId() != null) {
            stockRepository.findByItemId(testItem.getId()).ifPresent(stockRepository::delete);
            itemRepository.delete(testItem);
        }
    }

    /**
     * Интеграционный тест: успешное пополнение остатка.
     * 
     * <p>Проверяет, что метод receiveStock() корректно увеличивает количество товара
     * и сохраняет изменения в базе данных.
     */
    @Test
    void receiveStockIntegrationSuccess() {
        // Arrange
        testItem = createAndSaveItem("Тестовый товар 1");
        Stock stock = createAndSaveStock(testItem, 10);

        // Act
        int result = stockService.receiveStock(testItem.getId(), 5);

        // Assert
        assertEquals(15, result);
        assertEquals(15, stockRepository.findByItemId(testItem.getId()).get().getQuantity());
    }

    /**
     * Интеграционный тест: пополнение нулевым количеством не меняет остаток.
     * 
     * <p>Проверяет, что добавление 0 единиц не приводит к изменению остатка.
     */
    @Test
    void receiveStockIntegrationZeroQuantity() {
        // Arrange
        testItem = createAndSaveItem("Тестовый товар 2");
        Stock stock = createAndSaveStock(testItem, 10);

        // Act
        int result = stockService.receiveStock(testItem.getId(), 0);

        // Assert
        assertEquals(10, result);
        assertEquals(10, stockRepository.findByItemId(testItem.getId()).get().getQuantity());
    }

    /**
     * Интеграционный тест: пополнение несуществующего остатка выбрасывает исключение.
     * 
     * <p>Проверяет, что при попытке пополнить несуществующий остаток
     * метод выбрасывает RuntimeException с корректным сообщением.
     */
    @Test
    void receiveStockIntegrationNotFoundThrowsException() {
        // Arrange
        testItem = createAndSaveItem("Тестовый товар 3");

        // Act & Assert
        var ex = assertThrows(RuntimeException.class, () -> {
            stockService.receiveStock(testItem.getId(), 5);
        });

        // Проверяем сообщение исключения
        String message = ex.getMessage();
        assertTrue(message.contains("Stock"), "Сообщение должно содержать 'Stock'");
    }

    /**
     * Интеграционный тест: успешное списание остатка.
     * 
     * <p>Проверяет, что метод writeOffStock() корректно уменьшает количество товара
     * и сохраняет изменения в базе данных.
     */
    @Test
    void writeOffStockIntegrationSuccess() {
        // Arrange
        testItem = createAndSaveItem("Тестовый товар 4");
        Stock stock = createAndSaveStock(testItem, 10);

        // Act
        int result = stockService.writeOffStock(testItem.getId(), 3);

        // Assert
        assertEquals(7, result);
        assertEquals(7, stockRepository.findByItemId(testItem.getId()).get().getQuantity());
    }

    /**
     * Интеграционный тест: списание всего остатка приводит к нулю.
     * 
     * <p>Проверяет, что списание точного количества остатка приводит к нулевому остатку.
     */
    @Test
    void writeOffStockIntegrationExactQuantity() {
        // Arrange
        testItem = createAndSaveItem("Тестовый товар 5");
        Stock stock = createAndSaveStock(testItem, 10);

        // Act
        int result = stockService.writeOffStock(testItem.getId(), 10);

        // Assert
        assertEquals(0, result);
        assertEquals(0, stockRepository.findByItemId(testItem.getId()).get().getQuantity());
    }

    /**
     * Интеграционный тест: списание отрицательного количества увеличивает остаток.
     * 
     * <p>Проверяет, что отрицательное списание интерпретируется как приход.
     */
    @Test
    void writeOffStockIntegrationNegativeQuantity() {
        // Arrange
        testItem = createAndSaveItem("Тестовый товар 6");
        Stock stock = createAndSaveStock(testItem, 10);

        // Act
        int result = stockService.writeOffStock(testItem.getId(), -5);

        // Assert
        assertEquals(15, result);
        assertEquals(15, stockRepository.findByItemId(testItem.getId()).get().getQuantity());
    }

    /**
     * Интеграционный тест: списание при недостаточном остатке выбрасывает исключение.
     * 
     * <p>Проверяет, что при попытке списать больше, чем есть, метод выбрасывает
     * RuntimeException с сообщением об недостаточном остатке.
     */
    @Test
    void writeOffStockIntegrationInsufficientStock() {
        // Arrange
        testItem = createAndSaveItem("Тестовый товар 7");
        createAndSaveStock(testItem, 10);

        // Act & Assert
        var ex = assertThrows(RuntimeException.class, () -> {
            stockService.writeOffStock(testItem.getId(), 15);
        });

        // Проверяем сообщение исключения
        String message = ex.getMessage();
        assertTrue(message.contains("Insufficient"), "Сообщение должно содержать 'Insufficient'");
    }

    /**
     * Интеграционный тест: пополнение отрицательным количеством уменьшает остаток.
     * 
     * <p>Проверяет, что отрицательное приход интерпретируется как списание.
     */
    @Test
    void receiveStockIntegrationNegativeQuantity() {
        // Arrange
        testItem = createAndSaveItem("Тестовый товар 8");
        Stock stock = createAndSaveStock(testItem, 10);

        // Act
        int result = stockService.receiveStock(testItem.getId(), -3);

        // Assert
        assertEquals(7, result);
        assertEquals(7, stockRepository.findByItemId(testItem.getId()).get().getQuantity());
    }

    /**
     * Вспомогательный метод: создает и сохраняет Item в базе данных.
     * 
     * @param name название товара
     * @return сохраненный объект Item с сгенерированным ID
     */
    private Item createAndSaveItem(String name) {
        Item item = new Item();
        item.setSku("SKU-" + name.hashCode());
        item.setName(name);
        item.setCategory("Тестовая категория");
        item.setMinStock(5);
        item.setActive(true);
        return itemRepository.save(item);
    }

    /**
     * Вспомогательный метод: создает и сохраняет Stock в базе данных.
     * 
     * @param item     товар, для которого создается остаток
     * @param quantity начальное количество
     * @return сохраненный объект Stock с сгенерированным ID
     */
    private Stock createAndSaveStock(Item item, int quantity) {
        Stock stock = new Stock();
        stock.setItem(item);
        stock.setQuantity(quantity);
        return stockRepository.save(stock);
    }
}
