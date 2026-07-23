package com.warehouse.service.import_export;

import com.warehouse.dto.response.item.ItemExportDto;
import com.warehouse.repository.ItemRepository;
import com.warehouse.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CsvExportService {
    private final ItemRepository      itemRepository;
    private final TransactionTemplate transactionTemplate;

    // --- ЭКСПОРТ ТОВАРОВ ---
    @Transactional(readOnly = true)
    public void exportItems(Writer writer) {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                writer.write('\uFEFF'); // BOM для Excel

                CSVFormat format = CSVFormat.DEFAULT.builder()
                                                    .setHeader("SKU", "Name", "Category", "Quantity", "Price")
                                                    .build();

                try (CSVPrinter printer = new CSVPrinter(writer, format);
                     Stream<ItemExportDto> itemStream = itemRepository.streamAllForExport()) {

                    itemStream.forEach(item -> {
                        try {
                            printer.printRecord(
                                    item.sku(),
                                    item.name(),
                                    item.category(),
                                    item.quantity(),
                                    item.price()
                            );
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
}
