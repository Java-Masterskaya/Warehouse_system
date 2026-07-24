package com.warehouse.dto.request.item;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record ItemImportRowDto(@NotBlank(message = "SKU не может быть пустым") String sku,

                               @NotBlank(message = "Название товара не может быть пустым") String name,

                               @NotBlank(message = "Категория не может быть пустой") String category,

                               @NotNull(message = "Количество обязательно") @PositiveOrZero(
                                       message = "Количество не может быть отрицательным") Integer quantity,

                               @NotNull(message = "Цена обязательна") @Positive(
                                       message = "Цена должна быть больше нуля") BigDecimal price,
                               @NotNull(message = "Себестоимость обязательна") @Positive(
                                       message = "Себестоимость должна быть больше нуля") BigDecimal cost) {
}
