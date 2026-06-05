package com.warehouse.service;

import com.warehouse.dto.requests.UserCreateRequest;
import com.warehouse.dto.responses.UserResponse;
import jakarta.transaction.Transactional;

public interface UserService {
    @Transactional
    UserResponse createUser(UserCreateRequest request);
}
