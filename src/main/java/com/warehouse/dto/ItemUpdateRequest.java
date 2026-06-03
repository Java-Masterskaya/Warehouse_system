package com.warehouse.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ItemUpdateRequest {

    @NotBlank(message = "Название товара не может быть пустым")
    private String name;

    @NotBlank(message = "Категория не может быть пустой")
    private String category;

    @Min(value = 0, message = "Минимальный остаток не может быть отрицательным")
    @NotNull(message = "Минимальный остаток не может быть null")
    private Integer minStock;
}