package com.warehouse.dto.response.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error, String message, Instant timestamp) {

    public ErrorResponse(String error, String message) {
        this(error, message, Instant.now());
    }
}
