package com.warehouse.dto.response.item;

import com.warehouse.dto.response.error.ItemImportErrorDto;

import java.util.List;

/**
 * Результат обработки импорта элементов.
 *
 * @param totalRows общее количество обработанных строк
 * @param imported  количество успешно импортированных элементов
 * @param failed    количество элементов, завершившихся ошибкой
 * @param errors    список деталей по каждой возникшей ошибке
 */
public record ItemImportResultDto(
        int totalRows,
        int imported,
        int failed,
        List<ItemImportErrorDto> errors
) {
    /**
     * Создаёт объект результата импорта, автоматически рассчитывая
     * количество ошибок на основе размера списка {@code errors}.
     *
     * @param totalRows общее количество обработанных строк
     * @param imported  количество успешно импортированных элементов
     * @param errors    список ошибок
     * @return заполненный объект результата импорта
     */
    public static ItemImportResultDto of(int totalRows, int imported, List<ItemImportErrorDto> errors) {
        List<ItemImportErrorDto> safeErrors = errors != null ? errors : List.of();
        return new ItemImportResultDto(totalRows, imported, safeErrors.size(), safeErrors);
    }
}
