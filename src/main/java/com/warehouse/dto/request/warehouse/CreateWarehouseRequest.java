package com.warehouse.dto.request.warehouse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWarehouseRequest(
        @NotBlank(message = "Название склада не может быть пустым")
        @Size(max = 100, message = "Название склада не должно превышать 100 символов")
        String name
) {
}
