package com.warehouse.dto.error;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.List;

@JsonSerialize(using = ValidationErrorResponseSerializer.class)
public record ValidationErrorResponse(String error, List<FieldError> fields) {

    public ValidationErrorResponse(String error, List<FieldError> fields) {
        this.error = error;
        this.fields = fields;
    }
}
