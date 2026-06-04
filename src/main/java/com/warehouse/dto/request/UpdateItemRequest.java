package com.warehouse.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateItemRequest(
        @NotBlank(message = "Название товара не может быть пустым")
        String name,

        @NotBlank(message = "Категория не может быть пустой")
        String category,

        @Min(value = 0, message = "Минимальный остаток не может быть отрицательным")
        @NotNull(message = "Минимальный остаток не может быть null")
        Integer minStock
) {
}