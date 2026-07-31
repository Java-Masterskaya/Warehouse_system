package com.warehouse.service;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import com.warehouse.dto.response.item.ItemImportResultDto;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.service.import_export.CsvImportService;
import com.warehouse.service.import_export.CsvItemParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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
    private CsvItemParser csvItemParser;

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

    @Test
    @DisplayName("Корректный подсчет строк для большого количества чанков")
    void shouldCorrectlySumProcessedRowsForMultipleChunks() {
        StringBuilder csvBuilder = new StringBuilder("SKU,Name,Category,Price,Cost\n");

        for (int i = 1; i <= 2500; i++) {
            csvBuilder.append("SKU-").append(i)
                      .append(",Товар ").append(i)
                      .append(",Категория,100.00,50.00\n");
        }

        InputStream inputStream = new ByteArrayInputStream(csvBuilder.toString().getBytes(StandardCharsets.UTF_8));
        Iterable<CsvItemParser.CsvChunk> chunks = csvItemParser.parseInChunks(inputStream);

        int totalProcessedRows = 0;
        for (CsvItemParser.CsvChunk chunk : chunks) {
            totalProcessedRows += chunk.processedRowsCount();
        }

        assertThat(totalProcessedRows).isEqualTo(2500);
    }

    @Test
    @DisplayName("Одна невалидная строка не должна откатывать всю транзакцию")
    void shouldRolldownImpostIfInvalidRowInChunk() {
        Category category = new Category();
        category.setName("Категория");
        categoryRepository.saveAndFlush(category);

        StringBuilder csvBuilder = new StringBuilder("SKU,Name,Category,Price,Cost\n");

        //Невалидная первая строка
        csvBuilder.append("SKU-").append(0)
                  .append(",Товар ").append(1)
                  .append(",Категория,10000000000000000000.00,50.00\n");

        for (int i = 1; i <= 2500; i++) {
            csvBuilder.append("SKU-").append(i)
                      .append(",Товар ").append(i)
                      .append(",Категория,100.00,50.00\n");
        }

        byte[] content = csvBuilder.toString().getBytes(StandardCharsets.UTF_8);

        MultipartFile multipartFile = new MockMultipartFile(
                "file",
                "items.csv",
                "text/csv",
                content
        );

        ItemImportResultDto result = csvImportService.importItems(multipartFile);

        System.out.println("====================Ошибки===================");
        for (ItemImportErrorDto error : result.errors()) {
            System.out.println(error);
        }
        System.out.println("=============================================");

        assertThat(result).isNotNull();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);

        assertThat(result.imported()).isEqualTo(2500);

        long savedCountInDb = itemRepository.count();
        assertThat(savedCountInDb).isEqualTo(2500);

        assertThat(itemRepository.existsBySku("SKU-invalid")).isFalse();
    }
}
