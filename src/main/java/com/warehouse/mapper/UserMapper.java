package com.warehouse.mapper;

import com.warehouse.dto.request.UserCreateRequest;
import com.warehouse.dto.response.UserResponse;
import com.warehouse.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    User toEntity(UserCreateRequest request);

    UserResponse toResponse(User user);
}
