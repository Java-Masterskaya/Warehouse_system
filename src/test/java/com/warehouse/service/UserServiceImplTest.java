package com.warehouse.service;

import com.warehouse.entity.User;
import com.warehouse.exception.SelfDeactivationException;
import com.warehouse.mapper.UserMapper;
import com.warehouse.repository.UserRepository;
import com.warehouse.service.user.UserService;
import com.warehouse.service.user.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты для UserServiceImpl: управление пользователями.
 * Проверяют: получение списка пользователей, деактивация, исключение при самодеактивации.
 */
@ExtendWith(MockitoExtension.class)
public class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        // Arrange: создаем маппер и сервис
        UserMapper userMapper = Mappers.getMapper(UserMapper.class);

        userService = new UserServiceImpl(userRepository, userMapper, passwordEncoder);
    }

    /**
     * Получение списка всех пользователей должно вызывать репозиторий.
     */
    @Test
    void shouldReturnAllUsers() {
        // Arrange: создаем тестовых пользователей
        User firstUser = createUser(1L);
        User secondUser = createUser(2L);

        when(userRepository.findAll()).thenReturn(List.of(firstUser, secondUser));

        // Act: получаем список пользователей
        userService.getUsers();

        // Assert: проверяем, что репозиторий вызван
        verify(userRepository).findAll();
    }

    /**
     * Деактивация пользователя должна устанавливать active = false.
     */
    @Test
    void successDeactivationUser() {
        // Arrange: создаем активного пользователя
        User user = createUser(1L);
        user.setActive(true);

        Long userId = user.getId();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // Act: деактивируем пользователя
        userService.deactivateUser(userId, 2L);

        // Assert: проверяем, что пользователь деактивирован
        verify(userRepository).findById(userId);
        assertFalse(user.isActive());
    }

    /**
     * Попытка деактивировать самого себя должна выбрасывать исключение.
     */
    @Test
    void shouldThrowExceptionWhenUserGoingToDeactivateHimself() {
        // Arrange: создаем пользователя
        User user = createUser(1L);

        // Act & Assert: пытаемся деактивировать самого себя
        assertThrows(SelfDeactivationException.class, () -> {
            userService.deactivateUser(user.getId(), 1L);
        });
    }

    /**
     * Вспомогательный метод для создания тестового пользователя.
     *
     * @param id ID пользователя
     * @return созданный объект User
     */
    private User createUser(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
