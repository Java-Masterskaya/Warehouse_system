package com.warehouse.service;

import com.warehouse.dto.response.item.ItemExportDto;
import com.warehouse.dto.response.movement.StockMovementExportDto;
import com.warehouse.entity.MovementType;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import com.warehouse.service.import_export.CsvExportServiceImpl;
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
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CsvExportServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private StockMovementRepository movementRepository;

    @Spy
    private TransactionTemplate transactionTemplate =
            new TransactionTemplate((PlatformTransactionManager) new PseudoTransactionManager());

    @InjectMocks
    private CsvExportServiceImpl csvExportService;

    @Test
    @DisplayName("Должен корректно формировать CSV со всеми полями и BOM")
    void exportItemsShouldWriteCorrectCsv() throws Exception {
        // Arrange
        ItemExportDto item1 =
                new ItemExportDto("SKU-001", "Молоко", "Молочные продукты", 10L, new BigDecimal("89.90"));
        ItemExportDto item2 = new ItemExportDto("SKU-002", "Хлеб, \"Ржаной\"", "Выпечка", 5L, new BigDecimal("45.00"));

        when(itemRepository.streamAllForExport(true)).thenReturn(Stream.of(item1, item2));

        StringWriter writer = new StringWriter();

        // Act
        csvExportService.exportItems(writer, true);

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

    //movement export test

    @Test
    @DisplayName("Должен корректно формировать CSV со всеми полями журнала движений")
    void exportMovementShouldWriteCorrectCsv() {
        // Arrange
        UUID transferUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 14, 30, 0);

        StockMovementExportDto movement1 =
                new StockMovementExportDto("SKU-100", "Молоко", "Главный склад", MovementType.RECEIVE, 50,
                        "admin_ivan", now, null // Обычный приход без трансфера
                );

        StockMovementExportDto movement2 =
                new StockMovementExportDto("SKU-200", "Хлеб, \"Ржаной\"", "Склад №2", MovementType.TRANSFER_OUT, -10,
                        "operator_petr", now.plusHours(1), transferUuid // Перевод между складами
                );

        when(movementRepository.streamAllForExport()).thenReturn(Stream.of(movement1, movement2));

        StringWriter writer = new StringWriter();

        // Act
        csvExportService.exportMovement(writer);

        // Assert
        String csvOutput = writer.toString();

        // 1. Проверяем наличие BOM метки (\uFEFF)
        assertThat(csvOutput).startsWith("\uFEFF");

        // 2. Проверяем заголовки
        assertThat(csvOutput).contains(
                "Item_sku,Item_name,Warehouse,Movement_type,Quantity,Creator,Created_at,Transfer_id");

        // 3. Проверяем первую строку (без transferId)
        assertThat(csvOutput).contains("SKU-100,Молоко,Главный склад,RECEIVE,50,admin_ivan,2026-07-23T14:30");

        // 4. Проверяем вторую строку (с эскейпингом кавычек в названии и заполнением transferId)
        assertThat(csvOutput).contains("SKU-200,\"Хлеб, \"\"Ржаной\"\"\",Склад №2,TRANSFER_OUT,-10,operator_petr,"
                + "2026-07-23T15:30,123e4567-e89b-12d3-a456-426614174000");
    }

    @Test
    @DisplayName("Экспорт товаров экранирует опасные префиксы формул (CSV Formula Injection)")
    void shouldSanitizeDangerousPrefixesOnItemExport() {
        Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action =
                    invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(Mockito.any());

        ItemExportDto dangerousItem = new ItemExportDto(
                "=SKU-001",
                "+Ноутбук",
                "@Электроника",
                10L,
                new BigDecimal("1000.00")
        );

        when(itemRepository.streamAllForExport(true)).thenReturn(Stream.of(dangerousItem));

        StringWriter writer = new StringWriter();

        csvExportService.exportItems(writer, true);

        String csvResult = writer.toString();

        assertThat(csvResult)
                .contains("'=SKU-001")
                .contains("'+Ноутбук")
                .contains("'@Электроника");
    }

    @Test
    @DisplayName("Экспорт всех товаров при active = null")
    void shouldExportAllItemsWhenActiveIsNull() {
        ItemExportDto item = new ItemExportDto("SKU-1", "Товар 1", "Категория", 10L, new BigDecimal("100.00"));
        when(itemRepository.streamAllForExport(isNull())).thenReturn(Stream.of(item));

        StringWriter writer = new StringWriter();
        csvExportService.exportItems(writer, null);

        String result = writer.toString();
        assertThat(result).contains("SKU-1", "Товар 1");
        Mockito.verify(itemRepository).streamAllForExport(isNull());
    }

    @Test
    @DisplayName("Экспорт только активных товаров при active = true")
    void shouldExportOnlyActiveItemsWhenActiveIsTrue() {
        ItemExportDto item = new ItemExportDto("SKU-2", "Активный товар", "Категория", 5L, new BigDecimal("200.00"));
        when(itemRepository.streamAllForExport(eq(true))).thenReturn(Stream.of(item));

        StringWriter writer = new StringWriter();
        csvExportService.exportItems(writer, true);

        String result = writer.toString();
        assertThat(result).contains("SKU-2", "Активный товар");
        Mockito.verify(itemRepository).streamAllForExport(eq(true));
    }

    @Test
    @DisplayName("Экспорт только неактивных товаров при active = false")
    void shouldExportOnlyInactiveItemsWhenActiveIsFalse() {
        ItemExportDto item = new ItemExportDto("SKU-3", "Неактивный товар", "Категория", 0L, new BigDecimal("50.00"));
        when(itemRepository.streamAllForExport(eq(false))).thenReturn(Stream.of(item));

        StringWriter writer = new StringWriter();
        csvExportService.exportItems(writer, false);

        String result = writer.toString();
        assertThat(result).contains("SKU-3", "Неактивный товар");
        Mockito.verify(itemRepository).streamAllForExport(eq(false));
    }
}
