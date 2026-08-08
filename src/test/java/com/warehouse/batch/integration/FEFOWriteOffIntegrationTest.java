package com.warehouse.batch.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.movement.ReceiveStockRequest;
import com.warehouse.dto.request.movement.WriteOffStockRequest;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Stock;
import com.warehouse.exception.InsufficientStockException;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.movement.StockMovementService;

/**
 * Интеграционный тест для проверки FEFO (First Expire First Out) списания.
 * Проверяет, что списание гасит партии по возрастанию срока годности.
 */
@SpringBootTest
@DisplayName("FEFO write-off integration test")
class FEFOWriteOffIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private StockMovementService stockMovementService;

    @Autowired
    private UserRepository userRepository;

    private Long itemId;
    private Long warehouseId;

    /**
     * Контекст пользователя с реальным id из базы.
     *
     * <p>Раньше здесь стояла захардкоженная единица. Она совпадает с id админа
     * из миграции V5 только до тех пор, пока эту строку никто не пересоздавал;
     * после — движение по складу падает на {@code stock_movements_user_id_fkey}.
     */
    private UserContext adminContext;

    @BeforeEach
    void setUp() {
        cleanDomainData();

        Category category = categoryRepository.findByNameIgnoreCase("Тестовая категория FEFO")
                .orElseGet(() -> categoryRepository.save(
                        Category.builder()
                                .name("Тестовая категория FEFO")
                                .build()
                ));

        // Создаем товар
        Item item = new Item();
        // SKU уникален на прогон: колонка под уникальным индексом. Суффикс в barcode — на будущее.
        String itemSuffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        item.setSku("SKU-FEO-" + itemSuffix);
        item.setName("Тестовый товар для FEFO");
        item.setCategory(category);
        item.setMinStock(5);
        item.setActive(true);
        item.setPrice(BigDecimal.valueOf(100.00));
        item.setCost(BigDecimal.valueOf(50.00));
        item.setBarcode("ITEM-TEST-FEFO-" + itemSuffix);
        itemRepository.save(item);

        // Создаем stock для товара (необходим для registerReceipt)
        Stock stock = new Stock();
        stock.setItem(item);
        stock.setWarehouse(defaultWarehouse());
        stock.setQuantity(0);
        stockRepository.save(stock);

        itemId = item.getId();
        warehouseId = stock.getWarehouse().getId();

        adminContext = userRepository.findByUsername("admin")
                .map(user -> new UserContext(user.getId(), user.getUsername()))
                .orElseThrow(() -> new IllegalStateException(
                        "Учётка admin из миграции V5 не найдена"));
    }

    /**
     * Проверяет, что FEFO списание гасит партии по возрастанию срока годности.
     * Создаем 3 партии с разными сроками годности и списываем меньшее количество,
     * чтобы убедиться, что гасится самаяearliest партия.
     */
    @Test
    @DisplayName("Should write off batches in FEFO order (earliest expiry first)")
    void shouldWriteOffInFEFOOrder() {
        // Arrange - Создаем 3 партии с разными сроками годности
        LocalDateTime now = LocalDateTime.now();

        // Партия 1: срок годности через 1 день (самая ранняя)
        ReceiveStockRequest receipt1 = new ReceiveStockRequest(
                itemId, 10, now.plusDays(1));
        stockMovementService.registerReceipt(receipt1, adminContext);

        // Партия 2: срок годности через 7 дней
        ReceiveStockRequest receipt2 = new ReceiveStockRequest(
                itemId, 20, now.plusDays(7));
        stockMovementService.registerReceipt(receipt2, adminContext);

        // Партия 3: срок годности через 30 дней (самая поздняя)
        ReceiveStockRequest receipt3 = new ReceiveStockRequest(
                itemId, 30, now.plusDays(30));
        stockMovementService.registerReceipt(receipt3, adminContext);

        // Проверяем, что все партии созданы
        List<Batch> batches = batchRepository.findByItemIdAndWarehouseIdOrderByExpiryDateAsc(
                itemId, warehouseId);
        assertThat(batches).hasSize(3);
        assertThat(batches).allSatisfy(batch ->
                assertThat(batch.getWarehouse().getId()).isEqualTo(warehouseId));
        assertThat(batches.get(0).getExpiryDate()).isBefore(batches.get(1).getExpiryDate());
        assertThat(batches.get(1).getExpiryDate()).isBefore(batches.get(2).getExpiryDate());

        // Act - Списываем 15 единиц
        WriteOffStockRequest writeOffRequest = new WriteOffStockRequest(itemId, 15);
        StockMovementResponse response = stockMovementService.writeOffReceipt(
                writeOffRequest, adminContext);

        // Assert 1 - Проверяем ответ движения
        assertThat(response.lowStockAlert()).isFalse();
        assertThat(response.quantity()).isEqualTo(15);
        assertThat(response.stockAfter()).isEqualTo(45); // 10+20+30 - 15 = 45

        // Assert 2 - Проверяем, что гасится первая партия (самая ранняя)
        batches = batchRepository.findByItemIdAndWarehouseIdOrderByExpiryDateAsc(
                itemId, warehouseId);
        assertThat(batches).hasSize(3);

        // Партия 1 (earliest) должна быть частично снята: 10 - 10 = 0
        assertThat(batches.get(0).getQuantity()).isEqualTo(0);
        assertThat(batches.get(0).getExpiryDate()).isEqualToIgnoringNanos(now.plusDays(1));

        // Партия 2 должна быть частично снята: 20 - 5 = 15
        assertThat(batches.get(1).getQuantity()).isEqualTo(15);
        assertThat(batches.get(1).getExpiryDate()).isEqualToIgnoringNanos(now.plusDays(7));

        // Партия 3 должна остаться без изменений: 30
        assertThat(batches.get(2).getQuantity()).isEqualTo(30);
        assertThat(batches.get(2).getExpiryDate()).isEqualToIgnoringNanos(now.plusDays(30));
    }

    /**
     * Проверяет, что при списании больших объемов гасятся все партии в правильном порядке.
     */
    @Test
    @DisplayName("Should write off multiple batches completely in FEFO order")
    void shouldWriteOffMultipleBatchesInFEFOOrder() {
        // Arrange - Создаем 3 партии
        LocalDateTime now = LocalDateTime.now();

        ReceiveStockRequest receipt1 = new ReceiveStockRequest(
                itemId, 10, now.plusDays(1));
        stockMovementService.registerReceipt(receipt1, adminContext);

        ReceiveStockRequest receipt2 = new ReceiveStockRequest(
                itemId, 20, now.plusDays(7));
        stockMovementService.registerReceipt(receipt2, adminContext);

        ReceiveStockRequest receipt3 = new ReceiveStockRequest(
                itemId, 30, now.plusDays(30));
        stockMovementService.registerReceipt(receipt3, adminContext);

        // Act - Списываем 45 единиц (гасим первые 2 партии полностью и часть третьей)
        WriteOffStockRequest writeOffRequest = new WriteOffStockRequest(itemId, 45);
        StockMovementResponse response = stockMovementService.writeOffReceipt(
                writeOffRequest, adminContext);

        // Assert 1 - Проверяем ответ
        assertThat(response.stockAfter()).isEqualTo(15); // 60 - 45 = 15

        // Assert 2 - Проверяем оставшиеся партии
        List<Batch> batches = batchRepository.findByItemIdAndWarehouseIdOrderByExpiryDateAsc(
                itemId, warehouseId);

        // Партия 1 должна быть полностью снята
        assertThat(batches.get(0).getQuantity()).isEqualTo(0);
        assertThat(batches.get(0).getExpiryDate()).isEqualToIgnoringNanos(now.plusDays(1));

        // Партия 2 должна быть полностью снята
        assertThat(batches.get(1).getQuantity()).isEqualTo(0);
        assertThat(batches.get(1).getExpiryDate()).isEqualToIgnoringNanos(now.plusDays(7));

        // Партия 3 должна остаться: 30 - 15 = 15
        assertThat(batches.get(2).getQuantity()).isEqualTo(15);
        assertThat(batches.get(2).getExpiryDate()).isEqualToIgnoringNanos(now.plusDays(30));
    }

    /**
     * Проверяет, что при попытке списать больше доступного количества выбрасывается исключение.
     */
    @Test
    @DisplayName("Should throw exception when trying to write off more than available")
    void shouldThrowExceptionWhenInsufficientStock() {
        // Arrange - Создаем 2 партии
        LocalDateTime now = LocalDateTime.now();

        ReceiveStockRequest receipt1 = new ReceiveStockRequest(
                itemId, 10, now.plusDays(1));
        stockMovementService.registerReceipt(receipt1, adminContext);

        ReceiveStockRequest receipt2 = new ReceiveStockRequest(
                itemId, 20, now.plusDays(7));
        stockMovementService.registerReceipt(receipt2, adminContext);

        // Act & Assert - Пытаемся списать больше, чем доступно (35 > 30)
        WriteOffStockRequest writeOffRequest = new WriteOffStockRequest(itemId, 35);

        InsufficientStockException exception = assertThrows(InsufficientStockException.class, () -> {
            stockMovementService.writeOffReceipt(writeOffRequest, adminContext);
        });

        assertThat(exception.getMessage()).contains("Insufficient stock");
    }
}
