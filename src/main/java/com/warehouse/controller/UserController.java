package com.warehouse.controller;

import com.warehouse.dto.request.user.UserCreateRequest;
import com.warehouse.dto.response.user.UserResponse;
import com.warehouse.security.UserPrincipal;
import com.warehouse.service.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Пользователи", description = "Управление пользователями (только ADMIN)")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    @Operation(summary = "Создать пользователя")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserCreateRequest request) {
        UserResponse updatedUser = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(updatedUser);
    }

    @Operation(summary = "Список всех пользователей")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        List<UserResponse> user = userService.getUsers();
        return ResponseEntity.status(HttpStatus.OK).body(user);
    }

    @Operation(summary = "Деактивировать пользователя")
    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivateUser(
            @PathVariable Long userId,
            @AuthenticationPrincipal UserPrincipal currentUser
    ) {
        userService.deactivateUser(userId, currentUser.getId());
        return ResponseEntity.noContent().build();
    }

}