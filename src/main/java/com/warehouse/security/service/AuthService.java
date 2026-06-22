package com.warehouse.security.service;

import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.response.security.LoginResponse;

public interface AuthService {
    LoginResponse login(LoginRequest request);
}