package com.warehouse.mapper;

import com.warehouse.dto.requests.UserCreateRequest;
import com.warehouse.dto.responses.UserResponse;
import com.warehouse.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "password", ignore = true)
    User toEntity(UserCreateRequest request);

    UserResponse toResponse(User user);
}
