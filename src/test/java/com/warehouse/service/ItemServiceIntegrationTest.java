package com.warehouse.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.audit.AuditRepository;
import com.warehouse.audit.entity.AuditAction;
import com.warehouse.audit.entity.AuditLogEntity;
import com.warehouse.audit.entity.EntityType;
import com.warehouse.dto.request.item.CreateItemRequest;
import com.warehouse.dto.request.item.UpdateItemRequest;
import com.warehouse.dto.response.item.ItemResponse;
import com.warehouse.entity.Category;
import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.UserPrincipal;
import com.warehouse.service.item.ItemService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class ItemServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ItemService itemService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void shouldCreateAuditRecordWhenItemCreated() {
        User admin = createActiveAdmin("Admin");

        try {
            setAuthentification(admin);

            ItemResponse item = createItem();

            AuditLogEntity audit = auditRepository.findTopByOrderByIdDesc();

            assertThat(audit.getAuditAction()).isEqualTo(AuditAction.CREATE);

            assertThat(audit.getEntityType()).isEqualTo(EntityType.ITEM);

            assertThat(audit.getEntityId()).isEqualTo(item.id());

            assertThat(audit.getUsername()).isEqualTo(admin.getUsername());

            JsonNode newNode = audit.getNewValue();

            assertThat(newNode.get("sku").asText()).isEqualTo(item.sku());

            assertThat(newNode.get("name").asText()).isEqualTo("Test item");

        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void shouldCreateAuditRecordWhenItemUpdated() {
        User admin = createActiveAdmin("Admin");

        try {
            setAuthentification(admin);

            ItemResponse item = createItem();

            itemService.updateItem(item.id(),
                    new UpdateItemRequest("New name", item.category(), 20, BigDecimal.valueOf(200),
                            BigDecimal.valueOf(150)));

            AuditLogEntity audit = auditRepository.findTopByOrderByIdDesc();

            assertThat(audit.getAuditAction()).isEqualTo(AuditAction.UPDATE);

            assertThat(audit.getEntityType()).isEqualTo(EntityType.ITEM);

            assertThat(audit.getEntityId()).isEqualTo(item.id());

            assertThat(audit.getUsername()).isEqualTo(admin.getUsername());

            JsonNode oldNode = audit.getOldValue();
            JsonNode newNode = audit.getNewValue();

            assertThat(oldNode.get("name").asText()).isEqualTo("Test item");

            assertThat(newNode.get("name").asText()).isEqualTo("New name");

//            assertThat(oldNode.get("category").asText()).isEqualTo("Category");
            assertThat(oldNode.get("category").get("name").asText()).isEqualTo("Category");

            assertThat(newNode.get("category").get("name").asText()).isEqualTo("Category");

            assertThat(oldNode.get("minStock").asInt()).isEqualTo(10);

            assertThat(newNode.get("minStock").asInt()).isEqualTo(20);

        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void shouldCreateAuditRecordWhenItemDelete() {
        User admin = createActiveAdmin("Admin");

        try {
            setAuthentification(admin);

            ItemResponse item = createItem();

            itemService.softDeleteItem(item.id());

            AuditLogEntity audit = auditRepository.findTopByOrderByIdDesc();

            assertThat(audit.getAuditAction()).isEqualTo(AuditAction.DEACTIVATE);

            assertThat(audit.getEntityType()).isEqualTo(EntityType.ITEM);

            assertThat(audit.getEntityId()).isEqualTo(item.id());

            assertThat(audit.getUsername()).isEqualTo(admin.getUsername());

            JsonNode oldNode = audit.getOldValue();
            JsonNode newNode = audit.getNewValue();

            assertThat(oldNode.get("active").asText()).isEqualTo("true");

            assertThat(newNode.get("active").asText()).isEqualTo("false");

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
                new CreateItemRequest(
                        "SKU-001-" + System.currentTimeMillis(),
                        "Test item",
                        categoryName,
                        10,
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(70)
                )
        );
    }
}
