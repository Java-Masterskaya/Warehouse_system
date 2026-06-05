package com.warehouse.service;

import com.warehouse.dto.requests.UserCreateRequest;
import com.warehouse.dto.responses.UserResponse;
import com.warehouse.entity.User;
import com.warehouse.exception.DuplicateUsernameException;
import com.warehouse.mapper.UserMapper;
import com.warehouse.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final BCryptPasswordEncoder bCryptPasswordEncoder = new BCryptPasswordEncoder();

    @Transactional
    @Override
    public UserResponse createUser(UserCreateRequest request) {
        log.debug("Create user with name '{}'", request.getUsername());
        if (userRepository.existsByUsername(request.getUsername())) {
            log.warn("User '{}' is already exist", request.getUsername());
            throw new DuplicateUsernameException(request.getUsername());
        }

        User user = userMapper.toEntity(request);
        user.setPassword(bCryptPasswordEncoder.encode(user.getPassword()));
        userRepository.save(user);

        log.info("User created: name={}", user.getUsername());
        return userMapper.toResponse(user);
    }
}
