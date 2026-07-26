package com.warehouse.dto.request.supplier;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSupplierRequest(
        @NotBlank(message = "Имя поставщика не может быть пустым")
        @Size(max = 100, message = "Имя поставщика не должно превышать 100 символов")
        String name
) {
}
