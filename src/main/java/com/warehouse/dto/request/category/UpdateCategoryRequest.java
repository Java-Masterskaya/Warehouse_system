package com.warehouse.dto.request.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCategoryRequest(

        @NotBlank(message = "Название категории не может быть пустым")
        @Size(max = 100, message = "Название категории не может быть длиннее 100 символов")
        String name
) {
}
