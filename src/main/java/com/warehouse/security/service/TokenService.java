package com.warehouse.security.service;

import com.warehouse.security.model.TokenPair;

import java.util.List;

public interface TokenService {
    TokenPair generateTokenPair(String username, Long userId, List<String> roles);
    String generateAccessToken(String username, Long userId, List<String> roles);
    String generateRefreshToken(String username, Long userId, List<String> roles);
    boolean validateRefreshToken(String refreshToken);
    void revokeRefreshToken(String refreshToken);
    void revokeAllUserTokens(Long userId);
    void rotateRefreshToken(String oldRefreshToken, String newRefreshToken);
    boolean isTokenBlacklisted(String accessToken);
    void blacklistAccessToken(String accessToken);
}
