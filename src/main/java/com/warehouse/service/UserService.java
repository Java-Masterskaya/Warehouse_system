package com.warehouse.service;

import com.warehouse.dto.requests.UserCreateRequest;
import com.warehouse.dto.responses.UserResponse;

public interface UserService {
    UserResponse createUser(UserCreateRequest request);
}
