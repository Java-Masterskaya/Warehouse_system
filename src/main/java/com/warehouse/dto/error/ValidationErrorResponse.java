package com.warehouse.dto.error;

import lombok.Getter;

import java.util.Map;

@Getter
public class ValidationErrorResponse extends ErrorResponse {
    private final Map<String, String> fields;

    public ValidationErrorResponse(String error, String message, Map<String, String> fields) {
        super(error, message);
        this.fields = fields;
    }
}
