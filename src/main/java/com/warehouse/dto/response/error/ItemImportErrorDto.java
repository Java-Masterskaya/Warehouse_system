package com.warehouse.dto.response.error;

/**
 * Сведения об ошибке, возникшей при импорте отдельной строки.
 *
 * @param rowNumber    номер строки в которой произошла ошибка
 * @param sku          артикул (SKU) товара, связанного с ошибкой (может быть {@code null}, если не удалось извлечь)
 * @param errorMessage текстовое описание возникшей ошибки
 */
public record ItemImportErrorDto(int rowNumber, String sku, String errorMessage) {
}
