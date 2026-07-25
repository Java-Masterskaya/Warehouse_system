package com.warehouse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.audit.AuditRepository;
import com.warehouse.audit.entity.AuditAction;
import com.warehouse.audit.entity.AuditLogEntity;
import com.warehouse.audit.entity.EntityType;
import com.warehouse.dto.request.user.UserCreateRequest;
import com.warehouse.dto.response.user.UserResponse;
import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.UserPrincipal;
import com.warehouse.security.service.TokenService;
import com.warehouse.service.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserService Integration Tests")
class UserServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private TokenService tokenService;

    private User   testUser;
    private String accessToken;
    private String refreshToken;

    @BeforeEach
    void setUp() {
        // Create test user for token tests
        testUser = new User();
        testUser.setUsername("testuser_deactivation_" + System.currentTimeMillis());
        testUser.setPassword(passwordEncoder.encode("password"));
        testUser.setRole(Role.ROLE_USER);
        testUser.setActive(true);
        testUser = userRepository.save(testUser);

        // Generate and store tokens using public method
        List<String> roles = List.of("ROLE_USER");
        var tokenPair = tokenService.generateTokenPair(testUser.getUsername(), testUser.getId(), roles);
        accessToken  = tokenPair.accessToken();
        refreshToken = tokenPair.refreshToken();
    }

    // ==================== EXISTING TEST ====================

    @Test
    @DisplayName("Concurrent deactivation of two last admins leaves at least one active admin")
    void concurrentDeactivationOfTwoLastAdminsLeavesAtLeastOneActiveAdmin() throws Exception {
        User firstAdmin = createActiveAdmin("concurrent-admin-1");
        User secondAdmin = createActiveAdmin("concurrent-admin-2");
        deactivateAllAdminsExcept(firstAdmin, secondAdmin);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            var firstResult = executor.submit(() -> {
                start.await();
                userService.deactivateUser(firstAdmin.getId(), secondAdmin.getId());
                return null;
            });

            var secondResult = executor.submit(() -> {
                start.await();
                userService.deactivateUser(secondAdmin.getId(), firstAdmin.getId());
                return null;
            });

            start.countDown();

            int failures = 0;

            try {
                firstResult.get();
            } catch (Exception e) {
                failures++;
            }

            try {
                secondResult.get();
            } catch (Exception e) {
                failures++;
            }

            long activeAdmins = userRepository.findAll().stream().filter(user -> user.getRole() == Role.ROLE_ADMIN)
                                              .filter(User::isActive).count();

            assertThat(activeAdmins).isGreaterThanOrEqualTo(1);
            assertThat(failures).isGreaterThanOrEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    // ==================== AUDIT TESTS ====================
    @Test
    @DisplayName("Should create audit record without password when user is created")
    void shouldCreateAuditRecordWhenUserCreated() throws Exception {
        User admin = createActiveAdmin("Admin_Creator");

        UserCreateRequest createRequest = new UserCreateRequest();
        createRequest.setUsername("NewUserToAudit");
        createRequest.setPassword("SuperSecret123!");
        createRequest.setRole(Role.ROLE_USER);

        UserResponse createdUser;

        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    new UserPrincipal(admin.getId(), admin.getUsername(), admin.getPassword(), admin.isActive(),
                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))), null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

            createdUser = userService.createUser(createRequest);

        } finally {
            SecurityContextHolder.clearContext();
        }

        AuditLogEntity audit = auditRepository.findTopByOrderByIdDesc();
        JsonNode newNode = audit.getNewValue();

        assertThat(audit.getAuditAction()).isEqualTo(AuditAction.CREATE);
        assertThat(audit.getEntityType()).isEqualTo(EntityType.USER);
        assertThat(audit.getEntityId()).isEqualTo(createdUser.getId());
        assertThat(audit.getUsername()).isEqualTo(admin.getUsername());

        assertThat(audit.getOldValue()).isNull();

        assertThat(newNode).isNotNull();
        assertThat(newNode.get("username").asText()).isEqualTo("NewUserToAudit");
        assertThat(newNode.get("role").asText()).isEqualTo(Role.ROLE_USER.name());

        assertThat(newNode.has("password")).isFalse();
    }

    @Test
    void shouldCreateAuditRecordWhenUserDeactivated() throws JsonProcessingException {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        User admin = createActiveAdmin("New admin");
        User user = userRepository.save(
                User.builder().username("User").password("User_pass").role(Role.ROLE_USER).active(true).createdAt(now)
                    .build());

        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    new UserPrincipal(admin.getId(), admin.getUsername(), admin.getPassword(), admin.isActive(),
                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))), null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

            userService.deactivateUser(user.getId(), admin.getId());
            User deactevated = userRepository.findById(user.getId()).get();
        } finally {
            SecurityContextHolder.clearContext();
        }

        AuditLogEntity audit = auditRepository.findTopByOrderByIdDesc();
        JsonNode oldNode = audit.getOldValue();
        JsonNode newNode = audit.getNewValue();

        assertThat(audit.getAuditAction()).isEqualTo(AuditAction.DEACTIVATE);
        assertThat(audit.getEntityType()).isEqualTo(EntityType.USER);
        assertThat(audit.getEntityId()).isEqualTo(user.getId());
        assertThat(audit.getUsername()).isEqualTo(admin.getUsername());
        assertThat(oldNode.get("active").asBoolean()).isTrue();
        assertThat(newNode.get("active").asBoolean()).isFalse();

        assertThat(newNode.has("password")).isFalse();
    }

    // ==================== NEW DEACTIVATION TESTS ====================

    @Test
    @DisplayName("Deactivation should revoke all tokens in Redis")
    void deactivationShouldRevokeAllTokensInRedis() {
        // 1. Verify tokens are stored and valid
        assertThat(tokenService.validateRefreshToken(refreshToken)).as(
                "Refresh token should be valid before deactivation").isTrue();
        assertThat(tokenService.isAccessTokenBlacklisted(accessToken)).as(
                "Access token should not be blacklisted before deactivation").isFalse();

        // 2. Deactivate user
        userService.deactivateUser(testUser.getId(), 999L); // 999 = admin id

        // 3. Verify tokens are revoked
        assertThat(tokenService.validateRefreshToken(refreshToken)).as(
                "Refresh token should be revoked after deactivation").isFalse();
        assertThat(tokenService.isAccessTokenBlacklisted(accessToken)).as(
                "Access token should be blacklisted after deactivation").isTrue();

        // 4. Verify user is deactivated in DB
        User deactivatedUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(deactivatedUser.isActive()).isFalse();
    }

    @Test
    @DisplayName("All user tokens should be revoked on deactivation")
    void deactivationShouldRevokeAllUserTokens() {
        // 1. Generate multiple tokens for the same user using public method
        List<String> roles = List.of("ROLE_USER");

        // Generate first pair
        var pair1 = tokenService.generateTokenPair(testUser.getUsername(), testUser.getId(), roles);
        String accessToken1 = pair1.accessToken();
        String refreshToken1 = pair1.refreshToken();

        // Generate second pair
        var pair2 = tokenService.generateTokenPair(testUser.getUsername(), testUser.getId(), roles);
        String accessToken2 = pair2.accessToken();
        String refreshToken2 = pair2.refreshToken();

        // 2. Verify tokens are valid before deactivation
        assertThat(tokenService.validateRefreshToken(refreshToken1)).as("First refresh token should be valid")
                                                                    .isTrue();
        assertThat(tokenService.validateRefreshToken(refreshToken2)).as("Second refresh token should be valid")
                                                                    .isTrue();
        assertThat(tokenService.isAccessTokenBlacklisted(accessToken1)).as(
                "First access token should not be blacklisted").isFalse();
        assertThat(tokenService.isAccessTokenBlacklisted(accessToken2)).as(
                "Second access token should not be blacklisted").isFalse();

        // 3. Deactivate user
        userService.deactivateUser(testUser.getId(), 999L);

        // 4. Verify ALL tokens are revoked
        assertThat(tokenService.validateRefreshToken(refreshToken1)).as("First refresh token should be revoked")
                                                                    .isFalse();
        assertThat(tokenService.validateRefreshToken(refreshToken2)).as("Second refresh token should be revoked")
                                                                    .isFalse();
        assertThat(tokenService.isAccessTokenBlacklisted(accessToken1)).as("First access token should be blacklisted")
                                                                       .isTrue();
        assertThat(tokenService.isAccessTokenBlacklisted(accessToken2)).as("Second access token should be blacklisted")
                                                                       .isTrue();

        // 5. Verify user is deactivated in DB
        User deactivatedUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(deactivatedUser.isActive()).isFalse();
    }

    @Test
    @DisplayName("Deactivation should remove refresh tokens from Redis")
    void deactivationShouldRemoveRefreshTokensFromRedis() {
        // 1. Verify refresh token exists in Redis
        assertThat(tokenService.validateRefreshToken(refreshToken)).isTrue();

        // 2. Deactivate user
        userService.deactivateUser(testUser.getId(), 999L);

        // 3. Verify refresh token is removed from Redis
        assertThat(tokenService.validateRefreshToken(refreshToken)).isFalse();
    }

    @Test
    @DisplayName("Deactivation should not affect other users' tokens")
    void deactivationShouldNotAffectOtherUsersTokens() {
        // 1. Create another user with tokens
        User otherUser = new User();
        otherUser.setUsername("other_user_" + System.currentTimeMillis());
        otherUser.setPassword(passwordEncoder.encode("password"));
        otherUser.setRole(Role.ROLE_USER);
        otherUser.setActive(true);
        otherUser = userRepository.save(otherUser);

        List<String> roles = List.of("ROLE_USER");
        var otherPair = tokenService.generateTokenPair(otherUser.getUsername(), otherUser.getId(), roles);
        String otherAccessToken = otherPair.accessToken();
        String otherRefreshToken = otherPair.refreshToken();

        // 2. Verify both users' tokens are valid
        assertThat(tokenService.validateRefreshToken(refreshToken)).isTrue();
        assertThat(tokenService.validateRefreshToken(otherRefreshToken)).isTrue();
        assertThat(tokenService.isAccessTokenBlacklisted(accessToken)).isFalse();
        assertThat(tokenService.isAccessTokenBlacklisted(otherAccessToken)).isFalse();

        // 3. Deactivate first user
        userService.deactivateUser(testUser.getId(), 999L);

        // 4. Verify only first user's tokens are revoked
        assertThat(tokenService.validateRefreshToken(refreshToken)).as("First user's refresh token should be revoked")
                                                                   .isFalse();
        assertThat(tokenService.validateRefreshToken(otherRefreshToken)).as(
                "Other user's refresh token should still be valid").isTrue();
        assertThat(tokenService.isAccessTokenBlacklisted(accessToken)).as(
                "First user's access token should be blacklisted").isTrue();
        assertThat(tokenService.isAccessTokenBlacklisted(otherAccessToken)).as(
                "Other user's access token should not be blacklisted").isFalse();
    }

    @Test
    @DisplayName("Deactivating already inactive user should still revoke tokens")
    void deactivatingAlreadyInactiveUserShouldStillRevokeTokens() {
        // 1. Deactivate user first time
        userService.deactivateUser(testUser.getId(), 999L);

        // 2. Verify tokens are revoked
        assertThat(tokenService.validateRefreshToken(refreshToken)).isFalse();
        assertThat(tokenService.isAccessTokenBlacklisted(accessToken)).isTrue();
        assertThat(userRepository.findById(testUser.getId()).orElseThrow().isActive()).isFalse();

        // 3. Try to deactivate again (should not throw)
        userService.deactivateUser(testUser.getId(), 999L);

        // 4. Verify still revoked
        assertThat(tokenService.validateRefreshToken(refreshToken)).isFalse();
        assertThat(tokenService.isAccessTokenBlacklisted(accessToken)).isTrue();
    }

    // ==================== HELPER METHODS ====================

    private User createActiveAdmin(String username) {
        return userRepository.findByUsername(username).map(user -> {
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole(Role.ROLE_ADMIN);
            user.setActive(true);
            return userRepository.save(user);
        }).orElseGet(() -> {
            User user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole(Role.ROLE_ADMIN);
            user.setActive(true);
            return userRepository.save(user);
        });
    }

    private void deactivateAllAdminsExcept(User firstAdmin, User secondAdmin) {
        userRepository.findAll().stream().filter(user -> user.getRole() == Role.ROLE_ADMIN).filter(User::isActive)
                      .filter(user -> !user.getId().equals(firstAdmin.getId()))
                      .filter(user -> !user.getId().equals(secondAdmin.getId())).forEach(user -> {
                          user.setActive(false);
                          userRepository.save(user);
                      });
    }
}
