package com.warehouse.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.warehouse.entity.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserCreateRequest {
    @NotBlank(message = "Имя пользователя не может быть пустым")
    @Size(max = 100)
    private String username;

    @Size(min = 8, max = 255, message = "Пароль не может быть пустой")
    @NotBlank(message = "Пароль не должен быть пустой")
    private String password;

    @NotNull(message = "Права пользователя не могут быть null")
    @Size(max = 20)
    private Role role;
}
