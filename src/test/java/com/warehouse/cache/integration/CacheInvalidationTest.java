package com.warehouse.cache.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.request.movement.ReceiveStockRequest;
import com.warehouse.dto.request.movement.WriteOffStockRequest;
import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.entity.Batch;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.entity.User;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.UserPrincipal;
import com.warehouse.service.item.ItemService;
import com.warehouse.service.movement.StockMovementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест для проверки инвалидации кэша.
 */
@TestPropertySource(properties = "bucket4j.enabled=false")
@SpringBootTest
class CacheInvalidationTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private ItemService itemService;

    @Autowired
    private StockMovementService stockMovementService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BatchRepository batchRepository;

    /**
     * Собственная учётка класса.
     *
     * <p>Раньше здесь переиспользовался общий {@code admin} из миграции V5, причём
     * {@link #createActiveAdmin(String)} перезаписывал ему пароль на {@code password}.
     * Соседние классы логинятся под {@code admin}/{@code secret} и после этого получали 401.
     */
    private static final String ADMIN_USERNAME = "cacheinvalidation-admin";

    private Long itemId;

    /**
     * Реальный id учётки из базы. Захардкоженная единица ломалась, как только
     * последовательность {@code users_id_seq} уезжала вперёд: движение по складу
     * падало на внешнем ключе {@code stock_movements_user_id_fkey}.
     */
    private Long adminUserId;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        // Общая очистка вместо самописного списка: он не знал про purchase_order_items,
        // и удаление items падало на внешнем ключе, стоило соседу оставить заказ поставщику.
        cleanDomainData();

        Category electronics = categoryRepository.save(
                Category.builder()
                        .name("Электроника")
                        .build()
        );

        Item item = new Item();
        item.setSku("SKU-001");
        item.setName("Ноутбук");
        item.setCategory(electronics);
        item.setMinStock(5);
        item.setActive(true);
        item.setPrice(BigDecimal.valueOf(1500.00));
        item.setCost(BigDecimal.valueOf(1000.00));
        item.setBarcode("ITEM-TEST-CACHEINV-001");
        itemRepository.save(item);

        Stock stock = new Stock();
        stock.setItem(item);
        stock.setWarehouse(defaultWarehouse());
        stock.setQuantity(10);
        stockRepository.save(stock);

        // Создаем начальную партию для синхронизации с stock.quantity
        Batch batch = new Batch();
        batch.setItem(item);
        batch.setWarehouse(defaultWarehouse());
        batch.setQuantity(10);
        batch.setExpiryDate(LocalDateTime.now().plusDays(365));
        batchRepository.save(batch);

        itemId = item.getId();

        setAuthentification();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * updateItem очищает кэш карточки товара.
     */
    @Test
    void updateItemShouldEvictItemCache() {
        itemService.getItem(itemId);

        UpdateItemRequest updateRequest = new UpdateItemRequest("Ноутбук Pro", "Электроника",
                10, BigDecimal.valueOf(1700.00), BigDecimal.valueOf(1100.00), null);
        itemService.updateItem(itemId, updateRequest);

        ItemDetailsResponse response = itemService.getItem(itemId);
        assertThat(response.getName()).isEqualTo("Ноутбук Pro");
        assertThat(response.getMinStock()).isEqualTo(10);
    }

    /**
     * softDeleteItem очищает кэш карточки товара.
     */
    @Test
    void softDeleteItemShouldEvictItemCache() {
        ItemDetailsResponse firstCall = itemService.getItem(itemId);
        assertThat(firstCall.isActive()).isTrue();

        itemService.softDeleteItem(itemId);

        try {
            itemService.getItem(itemId);
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("неактивен");
        }
    }

    /**
     * receiveMovement очищает кэш карточки товара.
     */
    @Test
    void receiveMovementShouldEvictItemCache() {
        ItemDetailsResponse firstCall = itemService.getItem(itemId);
        assertThat(firstCall.getCurrentStock()).isEqualTo(10);

        ReceiveStockRequest movementRequest = new ReceiveStockRequest(
                itemId, 5, LocalDateTime.now().plusDays(1));
        stockMovementService.registerReceipt(movementRequest,
                adminContext());

        ItemDetailsResponse response = itemService.getItem(itemId);
        assertThat(response.getCurrentStock()).isEqualTo(15);
    }

    /**
     * writeOffMovement очищает кэш карточки товара.
     */
    @Test
    void writeOffMovementShouldEvictItemCache() {
        // Сначала создаем партию через приход
        ReceiveStockRequest receiptRequest = new ReceiveStockRequest(
                itemId, 10, LocalDateTime.now().plusDays(1));
        stockMovementService.registerReceipt(receiptRequest,
                adminContext());

        // Проверяем, что приход сработал
        ItemDetailsResponse response1 = itemService.getItem(itemId);
        assertThat(response1.getCurrentStock()).isEqualTo(20);

        // Теперь списываем
        WriteOffStockRequest writeOffRequest = new WriteOffStockRequest(itemId, 3);
        stockMovementService.writeOffReceipt(writeOffRequest,
                adminContext());

        ItemDetailsResponse response2 = itemService.getItem(itemId);
        assertThat(response2.getCurrentStock()).isEqualTo(17);
    }

    private com.warehouse.dto.UserContext adminContext() {
        return new com.warehouse.dto.UserContext(adminUserId, ADMIN_USERNAME);
    }

    private void setAuthentification() {
        User admin = createActiveAdmin(ADMIN_USERNAME);
        adminUserId = admin.getId();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(admin.getId(), admin.getUsername(), admin.getPassword(), admin.isActive(),
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private User createActiveAdmin(String username) {
        return userRepository.findByUsername(username).map(user -> {
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole(Role.ROLE_ADMIN);
            user.setActive(true);
            return userRepository.save(user);
        }).orElseGet(() -> {
            User user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole(Role.ROLE_ADMIN);
            user.setActive(true);
            return userRepository.save(user);
        });
    }
}
