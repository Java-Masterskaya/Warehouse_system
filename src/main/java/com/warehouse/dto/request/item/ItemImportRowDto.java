package com.warehouse.dto.request.item;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ItemImportRowDto(@Size(max = 100)
                               @NotBlank(message = "SKU не может быть пустым") String sku,

                               @Size(max = 255)
                               @NotBlank(message = "Название товара не может быть пустым") String name,

                               @Size(max = 100)
                               @NotBlank(message = "Категория не может быть пустой") String category,

                               @NotNull
                               @Digits(integer = 17, fraction = 2)
                               @PositiveOrZero(message = "Цена не может быть отрицательной") BigDecimal price,

                               @NotNull
                               @Digits(integer = 17, fraction = 2)
                               @PositiveOrZero(message = "Себестоимость не может быть отрицательной") BigDecimal cost) {
}
