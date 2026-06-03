package com.warehouse.dto.error;

import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
public record ValidationErrorResponse(String error, String message, Instant timestamp, List<FieldError> fields) {

    public ValidationErrorResponse(String error, String message, List<FieldError> fields) {
        this(error, message, Instant.now(), fields);
    }
}
