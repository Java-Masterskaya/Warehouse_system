package com.warehouse.security.service;

import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.request.security.LogoutRequest;
import com.warehouse.dto.request.security.RefreshRequest;
import com.warehouse.dto.response.security.LoginResponse;
import com.warehouse.dto.response.security.RefreshResponse;

/**
 * Сервис аутентификации.
 * <p>
 * Реализует основные операции с токенами:
 * <ul>
 *   <li><b>Login:</b> Аутентификация пользователя с выдачей пары токенов</li>
 *   <li><b>Refresh:</b> Обновление access токена с ротацией refresh</li>
 *   <li><b>Logout:</b> Отзыв текущей сессии</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>Поток работы:</b>
 * <ol>
 *   <li>Пользователь логинится -> получает access (короткий) + refresh (длинный)</li>
 *   <li>При истечении access -> клиент отправляет refresh на /refresh</li>
 *   <li>Сервер проверяет refresh, создает новую пару токенов</li>
 *   <li>Старый refresh инвалидируется (ротация)</li>
 *   <li>При повторном использовании старого refresh -> отзыв всех токенов</li>
 * </ol>
 * </p>
 *
 */
public interface AuthService {
    LoginResponse login(LoginRequest request);
    RefreshResponse refresh(RefreshRequest request);
    void logout(LogoutRequest request);
}