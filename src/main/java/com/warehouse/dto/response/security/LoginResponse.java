package com.warehouse.dto.response.security;

public record LoginResponse(String token, long expiresIn) {
}
