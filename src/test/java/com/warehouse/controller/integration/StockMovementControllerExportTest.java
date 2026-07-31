package com.warehouse.controller.integration;

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
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.service.import_export.CsvExportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest
public class StockMovementControllerExportTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StockMovementRepository movementRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CsvExportService csvExportService;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Экспорт движений доступен для ADMIN и возвращает файл movements.csv")
    void exportMovementsWhenAdminShouldReturnCsvStream()
            throws Exception {
        LocalDateTime created = LocalDateTime.now();
        fillDb(1, created);
        MvcResult mvcResult =
                mockMvc.perform(get("/api/movements/export")).andExpect(request().asyncStarted()).andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk())
                                      .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8"))
                                      .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                                              "attachment; filename=\"movements.csv\""))
                                      .andReturn();

        String actualContent = dispatched.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String expected = "\uFEFFItem_sku,Item_name,Warehouse,Movement_type,Quantity,Creator,Created_at,Transfer_id\n"
                + "SKU-1,Товар,Default Warehouse,ADJUSTMENT,5,Name," + created.format(DateTimeFormatter.ofPattern(
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")) + ",\n";
        assertThat(actualContent).isEqualToIgnoringWhitespace(expected);
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Экспорт движений запрещен обычным пользователям — 403 Forbidden")
    void exportMovementsWhenUserShouldReturnForbidden()
            throws Exception {
        mockMvc.perform(get("/api/movements/export")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Экспорт движений запрещен неавторизованным — 401 Unauthorized")
    void exportMovementsWhenAnonymousShouldReturnUnauthorized()
            throws Exception {
        mockMvc.perform(get("/api/movements/export")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Экспорт большого журнала отрабатывает в асинхронном режиме без обрыва")
    void shouldStreamLargeCsvExportSuccessfully()
            throws Exception {
        fillDb(10000, LocalDateTime.now());

        System.out.println("DB count: " + movementRepository.count());
        System.out.println("1: " + movementRepository.findById(1L));
        System.out.println("99: " + movementRepository.findById(99L));
        MvcResult mvcResult = mockMvc.perform(get("/api/movements/export"))
                                     .andExpect(status().isOk())
                                     .andExpect(request().asyncStarted())
                                     .andReturn();
        System.out.println("Async started: " + mvcResult.getRequest().isAsyncStarted());

        long startTime = System.currentTimeMillis();
        while (mvcResult.getRequest().getAsyncContext() != null && mvcResult.getRequest()
                                                                            .getAsyncContext()
                                                                            .hasOriginalRequestAndResponse()
                && System.currentTimeMillis() - startTime < 5000) {
            Thread.sleep(100);
        }

        MvcResult finalResult = mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk()).andReturn();
        String content = finalResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).isNotBlank();
    }

    private void fillDb(int records, LocalDateTime time)
            throws Exception {

        Category category = categoryRepository.findByNameIgnoreCase("Категория").orElseGet(() -> {
            Category cat = new Category();
            cat.setName("Категория");
            return categoryRepository.saveAndFlush(cat);
        });

        Item item = new Item();
        item.setSku("SKU-1");
        item.setActive(true);
        item.setName("Товар");
        item.setCategory(category);
        item.setCost(new BigDecimal(100));
        item.setPrice(new BigDecimal(300));
        item.setMinStock(0);

        Item savedItem = itemRepository.saveAndFlush(item);

        Warehouse warehouse = warehouseRepository.findByDefaultWarehouseTrue()
                                                 .orElseGet(() -> warehouseRepository.save(Warehouse.builder()
                                                                                                    .name("Name")
                                                                                                    .defaultWarehouse(
                                                                                                            false)
                                                                                                    .build()));
        User user = userRepository.save(
                User.builder()
                    .username("Name")
                    .password("pass")
                    .role(Role.ROLE_ADMIN)
                    .active(true)
                    .createdAt(LocalDateTime.now())
                    .build());

        for (int i = 1; i <= records; i++) {
            MovementType type;
            int quantity;
            if (i % 2 == 0) {
                type     = MovementType.RECEIVE;
                quantity = 10;
            } else if (i % 3 == 0) {
                type     = MovementType.WRITE_OFF;
                quantity = 1;
            } else {
                type     = MovementType.ADJUSTMENT;
                quantity = 5;
            }
            movementRepository.save(StockMovement.builder()
                                                 .item(savedItem)
                                                 .warehouse(warehouse)
                                                 .user(user)
                                                 .type(type)
                                                 .quantity(quantity)
                                                 .createdAt(
                                                         time)
                                                 .build());
        }
    }
}
