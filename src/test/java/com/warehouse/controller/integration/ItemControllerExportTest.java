package com.warehouse.controller.integration;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.entity.Category;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.PurchaseOrderItemRepository;
import com.warehouse.repository.PurchaseOrderRepository;
import com.warehouse.repository.StockAlertRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.repository.StockRepository;
import com.warehouse.repository.StockReserveRepository;
import com.warehouse.service.import_export.CsvExportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ItemControllerExportTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CsvExportService csvExportService;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockMovementRepository movementRepository;

    @Autowired
    private StockReserveRepository reserveRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockAlertRepository    stockAlertRepository;
    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clearDatabase() {
        purchaseOrderItemRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
        stockAlertRepository.deleteAll();
        // 1. Сначала удаляем движения (они ссылаются на товары и склады)
        movementRepository.deleteAll();

        // 2. Затем удаляем остатки (они ссылаются на товары)
        stockRepository.deleteAll();

        // 3. Теперь можно безопасно удалить товары (они ссылаются на категории)
        itemRepository.deleteAll();

        // 4. И в самом конце — категории и склады (родители)
        categoryRepository.deleteAll();
        // warehouseRepository.deleteAll(); // если нужно
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Экспорт доступен для ADMIN и возвращает асинхронный стрим CSV")
    void exportItemsWhenAdminShouldReturnCsvStream()
            throws Exception {
        fillDb(1);
        MvcResult mvcResult = mockMvc.perform(get("/api/items/export")).andExpect(
                                             request().asyncStarted())
                                     .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(mvcResult))
                                      .andExpect(status().isOk())
                                      .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8"))
                                      .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                                              "attachment; filename=\"items.csv\""))
                                      .andReturn();

        String actualContent = dispatched.getResponse().getContentAsString(StandardCharsets.UTF_8);

        // Проверяем гибко: файл не пустой, содержит нужные заголовки и нашу добавленную строку
        assertThat(actualContent)
                .isNotNull()
                .contains("SKU,Name,Category,Quantity,Price") // заголовки на месте
                .contains("SKU-1,Товар 1,Категория,0,100.00");  // наш товар гарантированно присутствует в выгрузке
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Экспорт запрещен для обычного пользователя (USER) — 403 Forbidden")
    void exportItemsWhenUserShouldReturnForbidden()
            throws Exception {
        mockMvc.perform(get("/api/items/export")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Экспорт запрещен без аутентификации — 401 Unauthorized")
    void exportItemsWhenAnonymousShouldReturnUnauthorized()
            throws Exception {
        mockMvc.perform(get("/api/items/export")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Экспорт большого журнала отрабатывает в асинхронном режиме без обрыва")
    void shouldStreamLargeCsvExportSuccessfully()
            throws Exception {
        fillDb(10000);
        MvcResult mvcResult = mockMvc.perform(get("/api/items/export"))
                                     .andExpect(status().isOk())
                                     .andExpect(request().asyncStarted())
                                     .andReturn();

        // Дожидаемся завершения асинхронного потока
        mockMvc.perform(asyncDispatch(mvcResult))
               .andExpect(status().isOk())
               .andExpect(content().contentType("text/csv;charset=UTF-8"))
               .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.isEmptyString())));
    }

    private void fillDb(int items)
            throws Exception {

        categoryRepository.findByNameIgnoreCase("Категория").orElseGet(() -> {
            Category category = new Category();
            category.setName("Категория");
            return categoryRepository.saveAndFlush(category);
        });

        StringBuilder csvBuilder = new StringBuilder("SKU,Name,Category,Price,Cost\n");

        for (int i = 1; i <= items; i++) {
            csvBuilder.append("SKU-").append(i)
                      .append(",Товар ").append(i)
                      .append(",Категория,100.00,50.00\n");
        }

        MockMultipartFile csvFile = new MockMultipartFile(
                "file",
                "items.csv",
                "text/csv",
                csvBuilder.toString().getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/items/import").file(csvFile)).andExpect(status().isOk());
    }
}
