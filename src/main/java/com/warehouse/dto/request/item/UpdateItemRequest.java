package com.warehouse.dto.request.item;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateItemRequest(
        @NotBlank(message = "Название товара не может быть пустым")
        String name,

        @NotBlank(message = "Категория не может быть пустой")
        String category,

        @Min(value = 0, message = "Минимальный остаток не может быть отрицательным")
        @NotNull(message = "Минимальный остаток не может быть null")
        Integer minStock,

        @PositiveOrZero(message = "Цена не может быть отрицательной")
        BigDecimal price,
        @PositiveOrZero(message = "Себестоимость не может быть отрицательной")
        BigDecimal cost
) {
}