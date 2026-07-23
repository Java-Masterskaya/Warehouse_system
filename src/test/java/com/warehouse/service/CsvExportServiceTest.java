package com.warehouse.service;

import com.warehouse.dto.response.item.ItemExportDto;
import com.warehouse.repository.ItemRepository;
import com.warehouse.service.import_export.CsvExportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class CsvExportServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @Spy
    private TransactionTemplate transactionTemplate = new TransactionTemplate(
            (PlatformTransactionManager) new PseudoTransactionManager());

    @InjectMocks
    private CsvExportService csvExportService;

    @Test
    @DisplayName("Должен корректно формировать CSV со всеми полями и BOM")
    void exportItemsShouldWriteCorrectCsv() throws Exception {
        // Arrange
        ItemExportDto item1 = new ItemExportDto("SKU-001", "Молоко", "Молочные продукты", 10L,
                new BigDecimal("89.90"));
        ItemExportDto item2 = new ItemExportDto("SKU-002", "Хлеб, \"Ржаной\"", "Выпечка", 5L, new BigDecimal("45.00"));

        Mockito.when(itemRepository.streamAllForExport()).thenReturn(Stream.of(item1, item2));

        StringWriter writer = new StringWriter();

        // Act
        csvExportService.exportItems(writer);

        // Assert
        String csvOutput = writer.toString();

        // 1. Проверяем наличие BOM для Excel (\uFEFF)
        assertThat(csvOutput).startsWith("\uFEFF");

        // 2. Проверяем заголовки и строки
        assertThat(csvOutput).contains("SKU,Name,Category,Quantity,Price")
                             .contains("SKU-001,Молоко,Молочные продукты,10,89.90")
                             // Commons CSV должен автоматически обернуть в кавычки значение с запятой/кавычками
                             .contains("SKU-002,\"Хлеб, \"\"Ржаной\"\"\",Выпечка,5,45.00");
    }

    // Заглушка для TransactionTemplate
    private static class PseudoTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
