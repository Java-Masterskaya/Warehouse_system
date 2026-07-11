package com.warehouse.security.service;

import com.warehouse.security.model.TokenPair;

import java.util.List;

/**
 * Сервис для управления JWT токенами.
 * <p>
 * Обеспечивает:
 * <ul>
 *   <li>Генерацию пары access + refresh токенов</li>
 *   <li>Хранение и валидацию refresh токенов в Redis</li>
 *   <li>Blacklist для отозванных access токенов</li>
 *   <li>Ротацию refresh токенов с защитой от повторного использования</li>
 *   <li>Массовый отзыв всех токенов пользователя</li>
 * </ul>
 * </p>
 *
 */
public interface TokenService {
    TokenPair generateTokenPair(String username, Long userId, List<String> roles);
    String generateAccessToken(String username, Long userId, List<String> roles);
    String generateRefreshToken(String username, Long userId, List<String> roles);
    boolean validateRefreshToken(String refreshToken);
    boolean isRefreshTokenReused(String refreshToken);
    void revokeRefreshToken(String refreshToken);
    void revokeAllUserTokens(Long userId);
    void rotateRefreshToken(String oldRefreshToken, String newRefreshToken);
    boolean isAccessTokenBlacklisted(String accessToken);
    void blacklistAccessToken(String accessToken);
    void blacklistAllUserAccessTokens(Long userId);
}
