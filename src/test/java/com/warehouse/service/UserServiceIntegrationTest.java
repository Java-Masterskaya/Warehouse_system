package com.warehouse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.AbstractIntegrationTest;
import com.warehouse.audit.AuditRepository;
import com.warehouse.audit.entity.AuditAction;
import com.warehouse.audit.entity.AuditLogEntity;
import com.warehouse.audit.entity.EntityType;
import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.repository.UserRepository;
import com.warehouse.security.UserPrincipal;
import com.warehouse.service.user.UserService;
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
    private ObjectMapper objectMapper;

    @Test
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
    }

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
