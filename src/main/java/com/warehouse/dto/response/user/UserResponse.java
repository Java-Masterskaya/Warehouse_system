package com.warehouse.dto.response.user;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class UserResponse {
    private Long id;
    private String username;
    private String role;
    private boolean active;
    private LocalDateTime createdAt;
}