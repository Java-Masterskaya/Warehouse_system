package com.warehouse.service;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.dto.response.error.ItemImportErrorDto;
import com.warehouse.dto.response.item.ItemImportResultDto;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.repository.BatchRepository;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.PurchaseOrderItemRepository;
import com.warehouse.repository.PurchaseOrderRepository;
import com.warehouse.repository.StockAlertRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.service.import_export.CsvImportService;
import com.warehouse.service.import_export.CsvItemParserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class CsvImportServiceIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CsvImportService csvImportService;

    @Autowired
    private ItemRepository          itemRepository;
    @Autowired
    private StockMovementRepository movementRepository;
    @Autowired
    private StockReserveRepository  reserveRepository;
    @Autowired
    private StockRepository         stockRepository;

    @Autowired
    private CsvItemParserService csvItemParserService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StockAlertRepository stockAlertRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    private BatchRepository batchRepository;

    @BeforeEach
    @AfterEach
    void clearDatabase() {
        reserveRepository.deleteAll();
        purchaseOrderItemRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
        stockAlertRepository.deleteAll();
        movementRepository.deleteAll();
        batchRepository.deleteAll();
        stockRepository.deleteAll();
        itemRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @Test
    @DisplayName("У новых импортированных товаров автоматически создается пустой сток в БД")
    void shouldCreateEmptyStockForImportedItems() {
        createCategory();

        String csvContent = "SKU,Name,Category,Price,Cost\nTEST-SKU-999,Чайник,Категория,3000.00,2000.00";
        MockMultipartFile file = new MockMultipartFile("file", "items.csv", "text/csv",
                csvContent.getBytes());

        ItemImportResultDto result = csvImportService.importItems(file);
        System.out.println(result.imported());
        System.out.println(result.failed());
        for (ItemImportErrorDto i : result.errors()) {
            System.out.println(i.sku() + ": " + i.errorMessage());
        }

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
        createCategory();
        StringBuilder csvBuilder = new StringBuilder("SKU,Name,Category,Price,Cost\n");

        for (int i = 1; i <= 2500; i++) {
            csvBuilder.append("SKU-").append(i)
                      .append(",Товар ").append(i)
                      .append(",Категория,100.00,50.00\n");
        }

        InputStream inputStream = new ByteArrayInputStream(csvBuilder.toString().getBytes(StandardCharsets.UTF_8));
        Iterable<CsvItemParserService.CsvChunk> chunks = csvItemParserService.parseInChunks(inputStream);

        int totalProcessedRows = 0;
        for (CsvItemParserService.CsvChunk chunk : chunks) {
            totalProcessedRows += chunk.processedRowsCount();
        }

        assertThat(totalProcessedRows).isEqualTo(2500);
    }

    @Test
    @DisplayName("Импорт продолжает работу и собирает ошибки, если часть строк не прошла валидацию")
    void shouldCollectErrorsAndImportValidRowsWhenSomeRowsAreInvalid() {
        createCategory();
        StringBuilder csvBuilder = new StringBuilder("SKU,Name,Category,Price,Cost\n");

        // Невалидная первая строка (неверный формат цены вызовет перехват в catch)
        csvBuilder.append("ART-0,Товар 0,Категория,INVALID_PRICE,50.00\n");

        // Остальные 499 строк — полностью валидные (в сумме ровно чанк из 500 строк)
        for (int i = 1; i <= 499; i++) {
            csvBuilder.append("ART-").append(i)
                      .append(",Товар ").append(i)
                      .append(",Категория,100.00,50.00\n");
        }

        byte[] content = csvBuilder.toString().getBytes(StandardCharsets.UTF_8);
        MultipartFile multipartFile = new MockMultipartFile("file", "items.csv", "text/csv", content);

        ItemImportResultDto result = csvImportService.importItems(multipartFile);

        assertThat(result).isNotNull();

        System.out.println(result.imported());
        System.out.println(result.failed());
        for (ItemImportErrorDto i : result.errors()) {
            System.out.println(i.sku() + ": " + i.errorMessage());
        }
        // Проверяем, что битая строка зафиксирована в ошибках
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);

        // Проверяем, что остальные 499 валидных строк успешно импортировались
        assertThat(result.imported()).isEqualTo(499);
        assertThat(itemRepository.count()).isEqualTo(499);

        assertThat(itemRepository.existsBySku("ART-0")).isFalse();
    }

    @Test
    @DisplayName(
            "При ошибке в одной из строк чанка, проблемный чанк обрабатывает валидные строки, а остальные чанки "
                    + "проходят нормально")
    void shouldHandleBatchErrorIndependentlyWithoutStoppingProcess() {
        createCategory();
        StringBuilder csvBuilder = new StringBuilder("SKU,Name,Category,Price,Cost\n");

        // Первый чанк (до 500 строк), на 10-й строке делаем ошибку формата цены
        for (int i = 1; i <= 500; i++) {
            if (i == 10) {
                csvBuilder.append("ART-10,Битый товар,Категория,BAD_PRICE,50.00\n");
            } else {
                csvBuilder.append("ART-").append(i)
                          .append(",Товар ").append(i)
                          .append(",Категория,100.00,50.00\n");
            }
        }

        // Второй чанк (строки с 501 по 1000) — полностью валидный
        for (int i = 501; i <= 1000; i++) {
            csvBuilder.append("ART-").append(i)
                      .append(",Товар ").append(i)
                      .append(",Категория,100.00,50.00\n");
        }

        byte[] content = csvBuilder.toString().getBytes(StandardCharsets.UTF_8);
        MultipartFile multipartFile = new MockMultipartFile("file", "items_batch.csv", "text/csv", content);

        ItemImportResultDto result = csvImportService.importItems(multipartFile);

        assertThat(result).isNotNull();

        System.out.println(result.imported());
        System.out.println(result.failed());
        for (ItemImportErrorDto i : result.errors()) {
            System.out.println(i.sku() + ": " + i.errorMessage());
        }

        // Ровно 1 ошибка на 10-й строке
        assertThat(result.failed()).isEqualTo(1);

        // 499 из первого чанка + 500 из второго = 999 успешно импортированных
        assertThat(result.imported()).isEqualTo(999);
        assertThat(itemRepository.count()).isEqualTo(999);
    }

    @Test
    @DisplayName(
            "При DB-конфликте уникальности SKU во время батча, транзакция не отменяет весь чанк и сохраняет остальные"
                    + " товары")
    void shouldFallbackToSingleInsertsWhenDbBatchFailsOnDuplicateKey() {
        Category category = createCategory();

        // Заранее сохраняем товар в БД, чтобы вызвать DataIntegrityViolationException во время jdbcTemplate
        // .batchUpdate()
        itemRepository.saveAndFlush(Item.builder()
                                        .sku("DUPLICATE-SKU")
                                        .name("Уже существующий")
                                        .category(category)
                                        .price(new BigDecimal("100.00"))
                                        .cost(new BigDecimal("50.00"))
                                        .build());

        // Формируем CSV, где 2-я строка вызовет ошибку ограничения целостности в PostgreSQL на этапе вставки
        String csvContent = """
                SKU,Name,Category,Price,Cost
                VALID-SKU-1,Первый товар,Категория,100.00,50.00
                DUPLICATE-SKU,Товар дубликат,Категория,200.00,150.00
                VALID-SKU-2,Второй товар,Категория,300.00,200.00
                """;

        MockMultipartFile file =
                new MockMultipartFile("file", "items.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        ItemImportResultDto result = csvImportService.importItems(file);

        // Должна зафиксироваться 1 ошибка базы данных
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst().sku()).isEqualTo("DUPLICATE-SKU");

        // Фактически вставлены только 2 новые уникальные строки
        assertThat(result.imported()).isEqualTo(2);

        // Проверяем физическое наличие товаров в Postgres
        assertThat(itemRepository.existsBySku("VALID-SKU-1")).isTrue();
        assertThat(itemRepository.existsBySku("VALID-SKU-2")).isTrue();
    }

    private Category createCategory() {
        return categoryRepository.findByNameIgnoreCase("Категория").orElseGet(() -> {
            Category category = new Category();
            category.setName("Категория");
            return categoryRepository.saveAndFlush(category);
        });
    }
}
