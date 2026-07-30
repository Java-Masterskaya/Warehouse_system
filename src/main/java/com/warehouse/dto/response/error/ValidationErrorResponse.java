package com.warehouse.dto.response.error;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@JsonSerialize(using = ValidationErrorResponseSerializer.class)
public record ValidationErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String error,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, implementation = Map.class,
                additionalPropertiesSchema = String.class,
                description = "Сообщения валидации по имени поля, например {\"sku\": \"must not be blank\"}")
        List<FieldError> fields) {

    public ValidationErrorResponse(String error, List<FieldError> fields) {
        this.error = error;
        this.fields = fields;
    }
}
