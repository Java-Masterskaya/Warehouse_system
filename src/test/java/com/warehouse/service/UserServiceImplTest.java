package com.warehouse.service;

import com.warehouse.audit.AuditContext;
import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.exception.LastAdminDeactivationException;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Mock
    private AuditContext auditContext;

    private UserService userService;

    @BeforeEach
    void setUp() {
        UserMapper userMapper = Mappers.getMapper(UserMapper.class);

        userService = new UserServiceImpl(userRepository, userMapper, passwordEncoder, auditContext);
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
        User deactivated = user;
        deactivated.setActive(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(deactivated)).thenReturn(deactivated);

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

    @Test
    void shouldThrowExceptionWhenDeactivatingLastActiveAdmin() {
        User admin = createUser(1L);
        admin.setRole(Role.ROLE_ADMIN);
        admin.setActive(true);

        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findActiveUsersByRoleForUpdate(Role.ROLE_ADMIN))
                .thenReturn(List.of(admin));

        assertThrows(LastAdminDeactivationException.class, () -> {
            userService.deactivateUser(1L, 2L);
        });

        assertTrue(admin.isActive());
        verify(userRepository, never()).save(admin);
    }

    @Test
    void shouldDeactivateAdminWhenMoreThanOneActiveAdminExists() {
        User firstAdmin = createUser(1L);
        firstAdmin.setRole(Role.ROLE_ADMIN);
        firstAdmin.setActive(true);

        User secondAdmin = createUser(2L);
        secondAdmin.setRole(Role.ROLE_ADMIN);
        secondAdmin.setActive(true);

        when(userRepository.findById(1L)).thenReturn(Optional.of(firstAdmin));
        when(userRepository.findActiveUsersByRoleForUpdate(Role.ROLE_ADMIN))
                .thenReturn(List.of(firstAdmin, secondAdmin));
        User deactivated = firstAdmin;
        deactivated.setActive(false);
        when(userRepository.save(deactivated)).thenReturn(deactivated);

        userService.deactivateUser(1L, 2L);

        assertFalse(firstAdmin.isActive());
        verify(userRepository).save(firstAdmin);
    }

    @Test
    void shouldDeactivateRegularUserWithoutCheckingActiveAdmins() {
        User user = createUser(1L);
        user.setRole(Role.ROLE_USER);
        user.setActive(true);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        User deactivated = user;
        deactivated.setActive(false);
        when(userRepository.save(deactivated)).thenReturn(deactivated);

        userService.deactivateUser(1L, 2L);

        assertFalse(user.isActive());
        verify(userRepository, never()).findActiveUsersByRoleForUpdate(Role.ROLE_ADMIN);
        verify(userRepository).save(user);
    }
    
    private User createUser(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
