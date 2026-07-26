package com.warehouse.dto.response.security;

public record RefreshResponse(
        String accessToken,
        String refreshToken,
        long expiresIn
) {
}
