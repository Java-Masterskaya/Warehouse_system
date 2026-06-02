package com.warehouse.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateItemRequest(
        @NotBlank @Size(max = 100) String sku,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 100) String category,
        @Min(0) int minStock
) {}
