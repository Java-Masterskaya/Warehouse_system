package com.warehouse.controller;

import com.warehouse.dto.requests.UserCreateRequest;
import com.warehouse.dto.responses.UserResponse;
import com.warehouse.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserCreateRequest request) {
        UserResponse updatedUser = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(updatedUser);
    }
}
