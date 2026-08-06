package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.Role;
import com.warehouse.entity.StockMovement;
import com.warehouse.entity.User;
import com.warehouse.entity.Warehouse;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.util.JwtUtil;
import com.warehouse.support.SqlCaptureStatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for movement history cursor pagination.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StockMovementHistoryCursorIntegrationTest.SqlCaptureConfiguration.class)
class StockMovementHistoryCursorIntegrationTest extends AbstractIntegrationTest {

    private static final String HISTORY_URL = V1_API_ROOT + "/movements/{itemId}/history";
    private static final LocalDateTime SHARED_CREATED_AT =
            LocalDateTime.of(2026, 1, 15, 12, 0, 0, 123_000_000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    private Category category;
    private Item item;
    private User admin;
    private String adminToken;
    private List<Long> expectedIds;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        category = categoryRepository.saveAndFlush(Category.builder()
                .name("Cursor history " + suffix)
                .build());
        item = createItem("cursor-history-" + suffix);
        admin = userRepository.saveAndFlush(User.builder()
                .username("cursor-history-admin-" + suffix)
                .password("test-password")
                .role(Role.ROLE_ADMIN)
                .active(true)
                .build());
        adminToken = jwtUtil.generateToken(admin.getUsername(), admin.getId(), List.of(Role.ROLE_ADMIN.name()));

        Warehouse warehouse = defaultWarehouse();
        List<StockMovement> movements = new ArrayList<>();
        for (int quantity = 1; quantity <= 5; quantity++) {
            movements.add(StockMovement.builder()
                    .item(item)
                    .warehouse(warehouse)
                    .user(admin)
                    .type(MovementType.RECEIVE)
                    .quantity(quantity)
                    .createdAt(SHARED_CREATED_AT)
                    .build());
        }

        expectedIds = stockMovementRepository.saveAllAndFlush(movements).stream()
                .map(StockMovement::getId)
                .sorted(Comparator.reverseOrder())
                .toList();
        SqlCaptureStatementInspector.clear();
    }

    @AfterEach
    void clearCapturedSql() {
        SqlCaptureStatementInspector.clear();
    }

    @Test
    void cursorTraversalReturnsEveryMovementOnceWithDescendingIdTieBreak() throws Exception {
        List<Long> actualIds = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        List<Boolean> hasNextValues = new ArrayList<>();
        String cursor = "";

        while (true) {
            assertThat(pageSizes).hasSizeLessThan(3);
            JsonNode response = getHistory(item.getId(), cursor, null, 2);
            JsonNode content = response.path("content");
            pageSizes.add(content.size());
            hasNextValues.add(response.path("hasNext").asBoolean());
            content.forEach(movement -> actualIds.add(movement.path("id").asLong()));

            if (!response.path("hasNext").asBoolean()) {
                assertThat(response.has("nextCursor")).isTrue();
                assertThat(response.path("nextCursor").isNull()).isTrue();
                break;
            }

            cursor = response.path("nextCursor").asText();
            assertThat(cursor).isNotBlank();
        }

        assertThat(pageSizes).containsExactly(2, 2, 1);
        assertThat(hasNextValues).containsExactly(true, true, false);
        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
        assertThat(actualIds).doesNotHaveDuplicates();

        List<String> movementSelects = SqlCaptureStatementInspector.statements().stream()
                .map(StockMovementHistoryCursorIntegrationTest::normalizeSql)
                .filter(sql -> sql.contains(" from stock_movements "))
                .toList();
        assertThat(movementSelects).hasSize(3);
        assertThat(movementSelects).allSatisfy(sql -> {
            assertThat(sql).doesNotContain(" offset ");
            assertThat(sql).doesNotContain("count(");
        });
        assertThat(movementSelects.subList(1, movementSelects.size())).allSatisfy(sql ->
                assertThat(compactSql(sql)).containsPattern(
                        "\\([^,()]+\\.created_at,[^,()]+\\.id\\)<\\(\\?,\\?\\)"
                )
        );
    }

    @Test
    void malformedCursorReturns400() throws Exception {
        mockMvc.perform(historyRequest(item.getId(), "not-a-valid-cursor", null, 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    void cursorForAnotherItemReturns400() throws Exception {
        String cursor = firstNextCursor(item.getId(), null);
        Item otherItem = createItem("foreign-cursor-item-" + UUID.randomUUID());

        mockMvc.perform(historyRequest(otherItem.getId(), cursor, null, 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    void cursorForAnotherMovementTypeReturns400() throws Exception {
        String cursor = firstNextCursor(item.getId(), MovementType.RECEIVE);

        mockMvc.perform(historyRequest(item.getId(), cursor, MovementType.WRITE_OFF, 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    void typeFilterTraversesOnlyMatchingMovements() throws Exception {
        List<StockMovement> writeOffs = new ArrayList<>();
        for (int quantity = 1; quantity <= 3; quantity++) {
            writeOffs.add(StockMovement.builder()
                    .item(item)
                    .warehouse(defaultWarehouse())
                    .user(admin)
                    .type(MovementType.WRITE_OFF)
                    .quantity(quantity)
                    .createdAt(SHARED_CREATED_AT)
                    .build());
        }
        List<Long> expectedWriteOffIds = stockMovementRepository.saveAllAndFlush(writeOffs).stream()
                .map(StockMovement::getId)
                .sorted(Comparator.reverseOrder())
                .toList();

        List<Long> actualIds = new ArrayList<>();
        String cursor = "";
        while (true) {
            JsonNode response = getHistory(item.getId(), cursor, MovementType.WRITE_OFF, 2);
            response.path("content").forEach(movement -> actualIds.add(movement.path("id").asLong()));
            if (!response.path("hasNext").asBoolean()) {
                break;
            }
            cursor = response.path("nextCursor").asText();
        }

        assertThat(actualIds).containsExactlyElementsOf(expectedWriteOffIds);
        assertThat(actualIds).doesNotHaveDuplicates();
    }

    private JsonNode getHistory(Long itemId, String cursor, MovementType type, int size) throws Exception {
        String body = mockMvc.perform(historyRequest(itemId, cursor, type, size))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private String firstNextCursor(Long itemId, MovementType type) throws Exception {
        JsonNode response = getHistory(itemId, "", type, 2);
        assertThat(response.path("hasNext").asBoolean()).isTrue();
        String cursor = response.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        return cursor;
    }

    private MockHttpServletRequestBuilder historyRequest(
            Long itemId,
            String cursor,
            MovementType type,
            int size
    ) {
        MockHttpServletRequestBuilder request = get(HISTORY_URL, itemId)
                .header("Authorization", "Bearer " + adminToken)
                .param("cursor", cursor)
                .param("size", Integer.toString(size));
        if (type != null) {
            request.param("type", type.name());
        }
        return request;
    }

    private Item createItem(String key) {
        return itemRepository.saveAndFlush(Item.builder()
                .sku(key)
                .name("Cursor history item " + key)
                .category(category)
                .minStock(1)
                .price(BigDecimal.TEN)
                .cost(BigDecimal.ONE)
                .active(true)
                .barcode("ITEM-TEST-" + key)
                .build());
    }

    private static String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String compactSql(String sql) {
        return sql.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCaptureConfiguration {

        @Bean
        HibernatePropertiesCustomizer sqlCaptureHibernatePropertiesCustomizer() {
            return properties -> properties.put(
                    "hibernate.session_factory.statement_inspector",
                    new SqlCaptureStatementInspector()
            );
        }
    }
}
