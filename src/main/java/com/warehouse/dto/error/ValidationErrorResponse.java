package com.warehouse.dto.error;

import java.time.Instant;
import java.util.List;

public record ValidationErrorResponse(String error, String message, Instant timestamp, List<FieldError> fields) {

    public ValidationErrorResponse(String error, String message, List<FieldError> fields) {
        this(error, message, Instant.now(), fields);
    }
}
