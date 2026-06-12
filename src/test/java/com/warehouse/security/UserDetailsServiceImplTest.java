package com.warehouse.security;

import com.warehouse.entity.Role;
import com.warehouse.entity.User;
import com.warehouse.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserDetailsServiceImpl userDetailsService;

    @Test
    void loadUserByUsernameReturnsUserDetails() {
        User user = User.builder()
                .username("admin")
                .password("hashed")
                .role(Role.ROLE_ADMIN)
                .active(true)
                .build();

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        UserDetails result = userDetailsService.loadUserByUsername("admin");

        assertThat(result.getUsername()).isEqualTo("admin");
        assertThat(result.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
        assertThat(result.isEnabled()).isTrue();
    }

    @Test
    void loadUserByUsernameActiveIsFalseDisablesUser() {
        User user = User.builder()
                .username("inactive")
                .password("hashed")
                .role(Role.ROLE_USER)
                .active(false)
                .build();

        when(userRepository.findByUsername("inactive")).thenReturn(Optional.of(user));

        UserDetails result = userDetailsService.loadUserByUsername("inactive");

        assertThat(result.isEnabled()).isFalse();
    }

    @Test
    void loadUserByUsernameNotFoundThrowsException() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("ghost");
    }
}
