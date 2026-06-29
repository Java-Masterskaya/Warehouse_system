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

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * Unit-тест для UserServiceImpl.
 * Тестирует операции управления пользователями.
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
        UserMapper userMapper = Mappers.getMapper(UserMapper.class);

        userService = new UserServiceImpl(userRepository, userMapper, passwordEncoder);
    }

    /**
     * Возвращает список всех пользователей.
     */
    @Test
    void shouldReturnAllUsers() {
        User firstUser = createUser(1L);
        User secondUser = createUser(2L);

        when(userRepository.findAll()).thenReturn(List.of(firstUser, secondUser));

        userService.getUsers();

        verify(userRepository).findAll();
    }

    /**
     * Успешная деактивация пользователя.
     */
    @Test
    void successDeactivationUser() {
        User user = createUser(1L);
        user.setActive(true);

        Long userId = user.getId();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.deactivateUser(userId, 2L);

        verify(userRepository).findById(userId);
        assertFalse(user.isActive());
    }

    /**
     * Выбрасывает исключение, когда пользователь пытается деактивировать самого себя.
     */
    @Test
    void shouldThrowExceptionWhenUserGoingToDeactivateHimself() {
        User user = createUser(1L);

        assertThrows(SelfDeactivationException.class, () -> {
            userService.deactivateUser(user.getId(), 1L);
        });
    }

    private User createUser(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
