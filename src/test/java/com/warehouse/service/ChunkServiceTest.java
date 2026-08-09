package com.warehouse.service;

import com.warehouse.dto.request.item.ItemImportRowDto;
import com.warehouse.dto.response.error.ImportErrorAccumulator;
import com.warehouse.entity.Category;
import com.warehouse.entity.Warehouse;
import com.warehouse.repository.WarehouseRepository;
import com.warehouse.service.import_export.ChunkService;
import com.warehouse.service.import_export.CsvItemParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChunkServiceTest {

    @Mock
    private WarehouseRepository        warehouseRepository;
    @Mock
    private JdbcTemplate               jdbcTemplate;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionTemplate        requiresNewTxTemplate;

    private ChunkService          chunkService;
    private Warehouse             defaultWarehouse;
    private Category              category;
    private Map<String, Category> categoryMap;

    @BeforeEach
    void setUp() {
        chunkService = new ChunkService(warehouseRepository, jdbcTemplate, transactionManager);

        defaultWarehouse = Warehouse.builder().id(1L).name("Default").build();
        category         = Category.builder().id(10L).name("электроника").build();
        categoryMap      = Map.of("электроника", category);

        when(warehouseRepository.findByDefaultWarehouseTrue())
                .thenReturn(Optional.of(defaultWarehouse));
    }

    @Test
    @DisplayName("Батчевое сохранение возвращает корректное число при наличии Statement.SUCCESS_NO_INFO (-2)")
    void saveChunkSuccessWithSuccessNoInfoStatus() {
        CsvItemParserService.ValidRowHolder row1 = createHolder(1, "SKU-1", "Товар 1");
        CsvItemParserService.ValidRowHolder row2 = createHolder(2, "SKU-2", "Товар 2");
        ImportErrorAccumulator accumulator = new ImportErrorAccumulator();

        when(jdbcTemplate.batchUpdate(anyString(), any(List.class)))
                .thenReturn(new int[]{Statement.SUCCESS_NO_INFO, 1});

        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenReturn(Map.of("SKU-1", 100L, "SKU-2", 101L));

        int imported = chunkService.saveChunk(List.of(row1, row2), accumulator, categoryMap);

        assertThat(imported).isEqualTo(2);
        assertThat(accumulator.getTotalErrors()).isZero();
    }

    @Test
    @DisplayName("При ошибке батча происходит перехват DataIntegrityViolationException и перевод в поштучный режим")
    void saveChunkSwitchesToSaveOneByOneOnBatchException() {
        CsvItemParserService.ValidRowHolder row1 = createHolder(1, "SKU-GOOD", "Товар 1");
        CsvItemParserService.ValidRowHolder row2 = createHolder(2, "SKU-BAD", "Товар 2");
        ImportErrorAccumulator accumulator = new ImportErrorAccumulator();

        when(jdbcTemplate.batchUpdate(anyString(), any(List.class)))
                .thenThrow(new DataIntegrityViolationException("Batch SKU conflict"));

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq("SKU-GOOD"), any(), any(), any(), any()))
                .thenReturn(100L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq("SKU-BAD"), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("Key (sku)=(SKU-BAD) already exists"));

        int imported = chunkService.saveChunk(List.of(row1, row2), accumulator, categoryMap);

        assertThat(imported).isEqualTo(1);
        assertThat(accumulator.getTotalErrors()).isEqualTo(1);
        assertThat(accumulator.getDetails().getFirst().sku()).isEqualTo("SKU-BAD");

        verify(jdbcTemplate, times(2)).queryForObject(anyString(), eq(Long.class), any(), any(), any(), any(), any());
    }

    private CsvItemParserService.ValidRowHolder createHolder(int rowNum, String sku, String name) {
        ItemImportRowDto dto =
                new ItemImportRowDto(sku, name, "электроника", new BigDecimal("100.00"), new BigDecimal("50.00"));
        return new CsvItemParserService.ValidRowHolder(rowNum, dto);
    }
}