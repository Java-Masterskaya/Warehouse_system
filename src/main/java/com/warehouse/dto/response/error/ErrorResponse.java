package com.warehouse.dto.response.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String error,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant timestamp) {

    public ErrorResponse(String error, String message) {
        this(error, message, Instant.now());
    }
}
