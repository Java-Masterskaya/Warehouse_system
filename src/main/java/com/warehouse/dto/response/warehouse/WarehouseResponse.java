package com.warehouse.dto.response.warehouse;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WarehouseResponse(
        Long id,
        String name,
        @JsonProperty("isDefault") boolean defaultWarehouse
) {
}
