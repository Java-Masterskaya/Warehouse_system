package com.warehouse.service.user;

import com.warehouse.dto.request.user.UserCreateRequest;
import com.warehouse.dto.response.user.UserResponse;

public interface UserService {
    UserResponse createUser(UserCreateRequest request);
}
