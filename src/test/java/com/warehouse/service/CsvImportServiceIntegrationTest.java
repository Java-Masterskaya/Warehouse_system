package com.warehouse.service;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.item.ItemImportResultDto;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.service.import_export.CsvImportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class CsvImportServiceIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CsvImportService csvImportService;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Интеграционный тест: у новых импортированных товаров автоматически создается пустой сток в БД")
    @Transactional
    void shouldCreateEmptyStockForImportedItems() {
        Category category = new Category();
        category.setName("Бытовая техника");
        Category savedCategory = categoryRepository.save(category);

        String csvContent = "Name,Category,SKU,Price,Cost\nЧайник,Бытовая техника,TEST-SKU-999,3000.00,2000.00";
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv", csvContent.getBytes());

        ItemImportResultDto result = csvImportService.importItems(file);

        assertThat(result.imported()).isEqualTo(1);

        Item createdItem = itemRepository.findBySku("TEST-SKU-999").orElseThrow();

        Long stockCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stock WHERE item_id = ? AND quantity = 0",
                Long.class,
                createdItem.getId()
        );

        assertThat(stockCount).isEqualTo(1L);
    }
}
