package com.warehouse.dto.request.movement;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StocktakeRequest(
        @NotNull(message = "ID товара не может быть пустым")
        Long itemId,

        @Min(value = 0, message = "Количество не может быть отрицательным")
        int countedQuantity
) {}