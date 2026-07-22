package com.warehouse.security.model;

public record TokenPair(
        String accessToken,
        String refreshToken
) {
}
