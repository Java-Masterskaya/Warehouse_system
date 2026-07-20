package com.warehouse.dto.response.security;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresIn
) {
}
