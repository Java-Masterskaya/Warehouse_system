package com.warehouse.service.import_export;

import com.warehouse.dto.response.item.ItemExportDto;
import com.warehouse.dto.response.movement.StockMovementExportDto;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CsvExportServiceImpl implements CsvExportService {
    private final ItemRepository          itemRepository;
    private final StockMovementRepository movementRepository;
    private final TransactionTemplate     transactionTemplate;

    // --- ЭКСПОРТ ТОВАРОВ ---
    @Override
    public void exportItems(Writer writer, Boolean isActive) {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                writer.write('\uFEFF'); // BOM для Excel

                CSVFormat format = CSVFormat.DEFAULT.builder()
                                                    .setHeader("SKU", "Name", "Category", "Quantity", "Price").build();

                try (CSVPrinter printer = new CSVPrinter(writer,
                        format); Stream<ItemExportDto> itemStream = itemRepository.streamAllForExport(isActive)) {

                    itemStream.forEach(item -> {
                        try {
                            printer.printRecord(sanitizeText(item.sku()),
                                    sanitizeText(item.name()),
                                    sanitizeText(item.category()), item.quantity(),
                                    item.price());
                        } catch (IOException e) {
                            // Если клиент разорвал соединение — бросаем специальное исключение
                            throw new UncheckedIOException(e);
                        }
                    });
                    printer.flush();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Ошибка записи CSV", e);
            }
        });
    }

    // --- ЭКСПОРТ ДВИЖЕНИЯ ТОВАРОВ ---
    @Override
    public void exportMovement(Writer writer) {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                writer.write('\uFEFF');

                CSVFormat format = CSVFormat.DEFAULT.builder()
                                                    .setHeader("Item_sku", "Item_name", "Warehouse", "Movement_type",
                                                            "Quantity", "Creator", "Created_at", "Transfer_id")
                                                    .build();

                try (CSVPrinter printer = new CSVPrinter(writer, format);
                     Stream<StockMovementExportDto> movementStream = movementRepository.streamAllForExport()) {

                    movementStream.forEach(movement -> {
                        try {
                            printer.printRecord(sanitizeText(movement.sku()),
                                    sanitizeText(movement.name()),
                                    sanitizeText(movement.warehouseName()),
                                    movement.movementType(),
                                    movement.quantity(),
                                    sanitizeText(movement.userName()),
                                    movement.createdAt(),
                                    movement.transferId());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                    printer.flush();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Ошибка записи CSV", e);
            }
        });
    }

    private String sanitizeText(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        String trimmed = value.trim();
        char firstChar = trimmed.charAt(0);
        if (firstChar == '=' || firstChar == '+' || firstChar == '-' || firstChar == '@') {
            return "'" + trimmed;
        }
        return trimmed;
    }
}
