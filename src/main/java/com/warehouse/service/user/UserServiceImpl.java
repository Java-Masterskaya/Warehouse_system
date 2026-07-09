package com.warehouse.service.user;

import com.warehouse.dto.request.user.UserCreateRequest;
import com.warehouse.dto.response.user.UserResponse;
import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.exception.DuplicateUsernameException;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.exception.LastAdminDeactivationException;
import com.warehouse.exception.SelfDeactivationException;
import com.warehouse.mapper.UserMapper;
import com.warehouse.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    @Override
    public UserResponse createUser(UserCreateRequest request) {
        log.debug("Create user with name '{}'", request.getUsername());
        if (userRepository.existsByUsername(request.getUsername())) {
            log.warn("User '{}' is already exist", request.getUsername());
            throw DuplicateUsernameException.forUsername(request.getUsername());
        }

        User user = userMapper.toEntity(request);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        userRepository.save(user);

        log.info("User created: name={}", user.getUsername());
        return userMapper.toResponse(user);
    }

    @Override
    public List<UserResponse> getUsers() {
        log.debug("Get users");
        return userRepository.findAll().stream()
                .map(userMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deactivateUser(Long userId, Long currentUserId) {
        log.debug("Deactivate user with id '{}' by user with id '{}'", userId, currentUserId);
        if (userId.equals(currentUserId)) {
            log.warn("User with id '{}' is going to deactivate himself", userId);
            throw SelfDeactivationException.forUser(userId);
        }

        User user = userRepository.findById(userId).orElseThrow(() -> {
            log.warn("User with id '{}' not found", userId);
            return EntityNotFoundException.forId("User", userId);
        });

        if (user.getRole() == Role.ROLE_ADMIN) {
            List<User> activeAdmins = userRepository.findActiveUsersByRoleForUpdate(Role.ROLE_ADMIN);

            if (activeAdmins.size() == 1) {
                log.warn("Attempt to deactivate the last active admin: userId={}", userId);
                throw LastAdminDeactivationException.forUser(userId);
            }
        }

        user.setActive(false);
        userRepository.save(user);
    }
}
