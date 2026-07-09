package com.warehouse.security.service;

import com.warehouse.dto.request.security.LoginRequest;
import com.warehouse.dto.request.security.LogoutRequest;
import com.warehouse.dto.request.security.RefreshRequest;
import com.warehouse.dto.response.security.LoginResponse;
import com.warehouse.dto.response.security.RefreshResponse;

public interface AuthService {
    LoginResponse login(LoginRequest request);
    RefreshResponse refresh(RefreshRequest request);
    void logout(LogoutRequest request);
}