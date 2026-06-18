package com.warehouse.controller.security;

import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.response.security.LoginResponse;
import com.warehouse.security.service.AuthService;
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
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Аутентификация", description = "Получение JWT-токена")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Войти в систему", description = "Возвращает JWT-токен по логину и паролю")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        log.debug("Login request received for user: {}", request.username());
        return authService.login(request);
    }
}
