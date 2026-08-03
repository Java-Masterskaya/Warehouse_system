package com.warehouse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.audit.AuditRepository;
import com.warehouse.audit.entity.AuditAction;
import com.warehouse.audit.entity.AuditLogEntity;
import com.warehouse.audit.entity.EntityType;
import com.warehouse.dto.UserContext;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.movement.ReceiveStockRequest;
import com.warehouse.dto.request.movement.WriteOffStockRequest;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.dto.response.movement.StockMovementResponse;
import com.warehouse.entity.Category;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Role;
import com.warehouse.entity.Stock;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.UserPrincipal;
import com.warehouse.service.item.ItemService;
import com.warehouse.service.movement.StockMovementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class StockMovementServiceIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private ItemService itemService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockMovementService stockMovementService;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void shouldCreateAuditRecordWhenStockReceiptRegistered() {
        User admin = createActiveAdmin("New admin");

        try {
            setAuthentification(admin);

            ItemResponse item = createItem();

            Stock stock = stockRepository.findByItemId(item.id()).orElseThrow();
            stock.setQuantity(100);
            stockRepository.save(stock);

            ReceiveStockRequest request = new ReceiveStockRequest(item.id(), 20, LocalDateTime.now().plusDays(1));

            UserContext ctx = new UserContext(admin.getId(), admin.getUsername());

            stockMovementService.registerReceipt(request, ctx);

            AuditLogEntity audit = auditRepository.findTopByOrderByIdDesc();

            assertThat(audit.getAuditAction()).isEqualTo(AuditAction.RECEIVE);

            assertThat(audit.getEntityType()).isEqualTo(EntityType.STOCK);

            assertThat(audit.getUsername()).isEqualTo(admin.getUsername());

            JsonNode oldNode = audit.getOldValue();
            JsonNode newNode = audit.getNewValue();

            assertThat(oldNode.get("itemId").asLong()).isEqualTo(item.id());

            assertThat(oldNode.get("quantity").asInt()).isEqualTo(100);

            assertThat(newNode.get("itemId").asLong()).isEqualTo(item.id());

            assertThat(newNode.get("quantity").asInt()).isEqualTo(120);

            Stock updatedStock = stockRepository.findByItemId(item.id()).orElseThrow();

            assertThat(updatedStock.getQuantity()).isEqualTo(120);

            StockMovement movement = stockMovementRepository.findTopByOrderByIdDesc();

            assertThat(movement.getType()).isEqualTo(MovementType.RECEIVE);

            assertThat(movement.getQuantity()).isEqualTo(20);

        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void shouldCreateAuditRecordWhenStockWriteOffRegistered() {
        User admin = createActiveAdmin("New admin");

        try {
            setAuthentification(admin);

            ItemResponse item = createItem();
            UserContext ctx = new UserContext(admin.getId(), admin.getUsername());
            stockMovementService.registerReceipt(new ReceiveStockRequest(item.id(),
                    100,
                    LocalDateTime.now().plusDays(10)), ctx);

            WriteOffStockRequest request = new WriteOffStockRequest(item.id(), 30);

            StockMovementResponse response = stockMovementService.writeOffReceipt(request, ctx);

            AuditLogEntity audit = auditRepository.findTopByOrderByIdDesc();

            assertThat(audit.getAuditAction()).isEqualTo(AuditAction.WRITE_OFF);

            assertThat(audit.getEntityType()).isEqualTo(EntityType.STOCK);

            JsonNode oldNode = audit.getOldValue();
            JsonNode newNode = audit.getNewValue();

            assertThat(oldNode.get("itemId").asLong()).isEqualTo(item.id());

            assertThat(oldNode.get("quantity").asInt()).isEqualTo(100);

            assertThat(newNode.get("quantity").asInt()).isEqualTo(70);

            Stock updatedStock = stockRepository.findByItemId(item.id()).orElseThrow();

            assertThat(updatedStock.getQuantity()).isEqualTo(70);

            StockMovement movement = stockMovementRepository.findTopByOrderByIdDesc();

            assertThat(movement.getType()).isEqualTo(MovementType.WRITE_OFF);

            assertThat(movement.getQuantity()).isEqualTo(30);

        } finally {
            SecurityContextHolder.clearContext();
        }
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

    private void setAuthentification(User admin) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(admin.getId(), admin.getUsername(), admin.getPassword(), admin.isActive(),
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private ItemResponse createItem() {
        String categoryName = "Category";

        if (!categoryRepository.existsByNameIgnoreCase(categoryName)) {
            Category category = new Category();
            category.setName(categoryName);
            categoryRepository.save(category);
        }

        return itemService.createItem(
                new CreateItemRequest("SKU-001-" + System.currentTimeMillis(), "Test item", categoryName, 10,
                        BigDecimal.valueOf(100), BigDecimal.valueOf(70), null));
    }
}
