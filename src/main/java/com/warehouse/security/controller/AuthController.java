package com.warehouse.security.controller;

import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.request.security.LogoutRequest;
import com.warehouse.dto.request.security.RefreshRequest;
import com.warehouse.dto.response.security.LoginResponse;
import com.warehouse.dto.response.security.RefreshResponse;
import com.warehouse.security.service.AuthService;
import com.warehouse.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping({ApiPaths.V1_API_ROOT + "/auth", ApiPaths.LEGACY_API_ROOT + "/auth"})
@RequiredArgsConstructor
@Tag(name = "Аутентификация", description = "Управление аутентификацией и токенами")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Войти в систему", description = "Возвращает access (по логину и паролю) и refresh токены")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        log.debug("Login request received for user: {}", request.username());
        return authService.login(request);
    }

    @Operation(summary = "Обновить access токен", description = "Обменивает refresh токен на новый access токен")
    @PostMapping("/refresh")
    public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {
        log.debug("Refresh token request received");
        return authService.refresh(request);
    }

    @Operation(summary = "Выйти из системы", description = "Отзывает refresh токен")
    @PostMapping("/logout")
    public void logout(@Valid @RequestBody LogoutRequest request) {
        log.debug("Logout request received");
        authService.logout(request);
    }
}
