package com.warehouse.service.user;

import com.warehouse.dto.request.user.UserCreateRequest;
import com.warehouse.dto.response.user.UserResponse;

import java.util.List;

public interface UserService {
    UserResponse createUser(UserCreateRequest request);

    List<UserResponse> getUsers();

    void deactivateUser(Long userId, Long currentUserId);
}
