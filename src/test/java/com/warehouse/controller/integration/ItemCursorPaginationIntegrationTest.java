package com.warehouse.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.pagination.KeysetCursorCodec;
import com.warehouse.pagination.KeysetCursorCodec.CursorContext;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for item keyset pagination and cursor ownership.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "USER")
@Import(ItemCursorPaginationIntegrationTest.SqlCaptureConfiguration.class)
class ItemCursorPaginationIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE_URL = "/api/items";
    private static final int PAGE_SIZE = 2;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private KeysetCursorCodec cursorCodec;

    private Category category;
    private String itemName;
    private List<Long> expectedIds;

    @BeforeEach
    void setUpItems() {
        String suffix = UUID.randomUUID().toString();
        category = categoryRepository.save(new Category(null, "Cursor-" + suffix));
        itemName = "Cursor tie " + suffix;

        List<Item> items = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            items.add(Item.builder()
                    .sku("CURSOR-" + suffix + "-" + index)
                    .name(itemName)
                    .category(category)
                    .minStock(0)
                    .active(true)
                    .price(BigDecimal.ZERO)
                    .cost(BigDecimal.ZERO)
                    .build());
        }

        expectedIds = itemRepository.saveAllAndFlush(items).stream()
                .map(Item::getId)
                .sorted()
                .toList();
        SqlCaptureStatementInspector.clear();
    }

    @AfterEach
    void cleanUpItems() {
        itemRepository.deleteAllByIdInBatch(expectedIds);
        categoryRepository.deleteById(category.getId());
        SqlCaptureStatementInspector.clear();
    }

    @Test
    void cursorTraversalReturnsEveryEqualNameItemOnceInIdOrderWithoutOffset() throws Exception {
        List<Long> visitedIds = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = "";
        boolean hasNext;
        int pageCount = 0;

        do {
            JsonNode page = getCursorPage(cursor, "name", "asc", category.getName());
            JsonNode content = page.get("content");
            pageSizes.add(content.size());
            content.forEach(item -> visitedIds.add(item.get("id").asLong()));

            hasNext = page.get("hasNext").asBoolean();
            JsonNode nextCursor = page.get("nextCursor");
            if (hasNext) {
                assertThat(nextCursor.isTextual()).isTrue();
                assertThat(nextCursor.asText()).isNotBlank();
                cursor = nextCursor.asText();
            } else {
                assertThat(nextCursor.isNull()).isTrue();
            }

            pageCount++;
            assertThat(pageCount).isLessThanOrEqualTo(3);
        } while (hasNext);

        assertThat(pageSizes).containsExactly(2, 2, 1);
        assertThat(visitedIds).containsExactlyElementsOf(expectedIds);
        assertThat(new HashSet<>(visitedIds)).hasSize(expectedIds.size());

        List<String> itemSelects = SqlCaptureStatementInspector.statements().stream()
                .map(ItemCursorPaginationIntegrationTest::normalizeSql)
                .filter(sql -> sql.contains(" from items "))
                .toList();

        assertThat(itemSelects).hasSize(3);
        assertThat(itemSelects).allSatisfy(sql -> {
            assertThat(sql).doesNotContain(" offset ");
            assertThat(sql).doesNotContain("count(");
        });
        assertThat(itemSelects.subList(1, itemSelects.size())).allSatisfy(sql ->
                assertThat(compactSql(sql))
                        .containsPattern("\\([^,()]+\\.name,[^,()]+\\.id\\)>\\(\\?,\\?\\)")
        );
    }

    @Test
    void malformedCursorReturnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .param("cursor", "%%%not-base64%%")
                        .param("category", category.getName())
                        .param("size", String.valueOf(PAGE_SIZE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    void descendingCursorTraversalUsesDescendingIdTieBreak() throws Exception {
        List<Long> visitedIds = new ArrayList<>();
        String cursor = "";
        int pageCount = 0;

        while (true) {
            JsonNode page = getCursorPage(cursor, "name", "desc", category.getName());
            page.get("content").forEach(item -> visitedIds.add(item.get("id").asLong()));
            pageCount++;
            assertThat(pageCount).isLessThanOrEqualTo(3);
            if (!page.get("hasNext").asBoolean()) {
                break;
            }
            cursor = page.get("nextCursor").asText();
        }

        List<Long> expectedDescendingIds = expectedIds.stream()
                .sorted(Comparator.reverseOrder())
                .toList();
        assertThat(visitedIds).containsExactlyElementsOf(expectedDescendingIds);
        assertThat(visitedIds).doesNotHaveDuplicates();
    }

    @Test
    void skuCursorTraversalUsesSortValueSeek() throws Exception {
        List<Long> visitedIds = new ArrayList<>();
        String cursor = "";
        int pageCount = 0;

        while (true) {
            JsonNode page = getCursorPage(cursor, "sku", "asc", category.getName());
            page.get("content").forEach(item -> visitedIds.add(item.get("id").asLong()));
            pageCount++;
            assertThat(pageCount).isLessThanOrEqualTo(3);
            if (!page.get("hasNext").asBoolean()) {
                break;
            }
            cursor = page.get("nextCursor").asText();
        }

        assertThat(visitedIds).containsExactlyElementsOf(expectedIds);
        assertThat(visitedIds).doesNotHaveDuplicates();
    }

    @Test
    void foreignEndpointCursorReturnsBadRequest() throws Exception {
        String foreignCursor = cursorCodec.encode(
                new CursorContext(
                        "movement-history",
                        "createdAt",
                        "desc",
                        List.of("1", "")
                ),
                "2026-01-01T00:00:00",
                1L
        );

        mockMvc.perform(get(BASE_URL)
                        .param("cursor", foreignCursor)
                        .param("category", category.getName())
                        .param("size", String.valueOf(PAGE_SIZE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    void cursorWithChangedSortReturnsBadRequest() throws Exception {
        String cursor = firstNextCursor();

        mockMvc.perform(get(BASE_URL)
                        .param("cursor", cursor)
                        .param("sort", "sku")
                        .param("order", "asc")
                        .param("category", category.getName())
                        .param("size", String.valueOf(PAGE_SIZE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    void cursorWithChangedDirectionReturnsBadRequest() throws Exception {
        String cursor = firstNextCursor();

        mockMvc.perform(get(BASE_URL)
                        .param("cursor", cursor)
                        .param("sort", "name")
                        .param("order", "desc")
                        .param("category", category.getName())
                        .param("size", String.valueOf(PAGE_SIZE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    void cursorWithChangedFilterReturnsBadRequest() throws Exception {
        String cursor = firstNextCursor();

        mockMvc.perform(get(BASE_URL)
                        .param("cursor", cursor)
                        .param("sort", "name")
                        .param("order", "asc")
                        .param("category", category.getName() + "-changed")
                        .param("size", String.valueOf(PAGE_SIZE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    void cursorWithChangedSearchReturnsBadRequest() throws Exception {
        String cursor = firstNextCursor();

        mockMvc.perform(get(BASE_URL)
                        .param("cursor", cursor)
                        .param("sort", "name")
                        .param("order", "asc")
                        .param("category", category.getName())
                        .param("search", itemName)
                        .param("size", String.valueOf(PAGE_SIZE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    void cursorCannotBeCombinedWithPage() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .param("cursor", "")
                        .param("page", "0")
                        .param("category", category.getName())
                        .param("size", String.valueOf(PAGE_SIZE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    void cursorPageSizeAboveLimitReturnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .param("cursor", "")
                        .param("category", category.getName())
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void offsetPageSizeAboveLimitReturnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .param("page", "0")
                        .param("category", category.getName())
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    private String firstNextCursor() throws Exception {
        JsonNode firstPage = getCursorPage("", "name", "asc", category.getName());
        assertThat(firstPage.get("hasNext").asBoolean()).isTrue();
        return firstPage.get("nextCursor").asText();
    }

    private JsonNode getCursorPage(
            String cursor,
            String sort,
            String order,
            String categoryName
    ) throws Exception {
        MvcResult result = mockMvc.perform(get(BASE_URL)
                        .param("cursor", cursor)
                        .param("sort", sort)
                        .param("order", order)
                        .param("category", categoryName)
                        .param("size", String.valueOf(PAGE_SIZE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.hasNext").isBoolean())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
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
