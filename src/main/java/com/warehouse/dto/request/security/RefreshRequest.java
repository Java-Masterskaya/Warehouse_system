package com.warehouse.dto.request.security;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        String accessToken,
        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {
}
