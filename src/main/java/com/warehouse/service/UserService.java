package com.warehouse.service;

import com.warehouse.dto.request.UserCreateRequest;
import com.warehouse.dto.response.UserResponse;

public interface UserService {
    UserResponse createUser(UserCreateRequest request);
}
