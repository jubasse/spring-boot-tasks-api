package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DatabaseUserDetailsService service;

    private User user(boolean enabled, UserRole... roles) {
        User user = new User();
        user.setEmail("jane@example.com");
        user.setPasswordHash("hash");
        user.setEnabled(enabled);
        user.setRoles(new HashSet<>(Set.of(roles)));
        return user;
    }

    @Test
    void mapsEnabledUserWithRolePrefixedAuthorities() {
        when(userRepository.findByEmailIgnoreCase("Jane@Example.com"))
                .thenReturn(Optional.of(user(true, UserRole.USER, UserRole.ADMIN)));

        UserDetails details = service.loadUserByUsername("Jane@Example.com");

        assertThat(details.getUsername()).isEqualTo("jane@example.com");
        assertThat(details.getPassword()).isEqualTo("hash");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void mapsDisabledUserAsDisabled() {
        when(userRepository.findByEmailIgnoreCase("jane@example.com"))
                .thenReturn(Optional.of(user(false, UserRole.USER)));

        UserDetails details = service.loadUserByUsername("jane@example.com");

        assertThat(details.isEnabled()).isFalse();
    }

    @Test
    void unknownEmailThrowsUsernameNotFound() {
        when(userRepository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    void updatePasswordStoresNewHashAndReturnsUpdatedDetails() {
        User entity = user(true, UserRole.USER);
        when(userRepository.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(entity));
        UserDetails current = service.loadUserByUsername("jane@example.com");

        UserDetails updated = service.updatePassword(current, "{argon2id}new-hash");

        assertThat(entity.getPasswordHash()).isEqualTo("{argon2id}new-hash");
        assertThat(updated.getPassword()).isEqualTo("{argon2id}new-hash");
        assertThat(updated.getUsername()).isEqualTo("jane@example.com");
        assertThat(updated.getAuthorities()).isEqualTo(current.getAuthorities());
    }

    @Test
    void updatePasswordThrowsWhenUserNoLongerExists() {
        UserDetails details = org.springframework.security.core.userdetails.User
                .withUsername("gone@example.com")
                .password("hash")
                .build();
        when(userRepository.findByEmailIgnoreCase("gone@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePassword(details, "{argon2id}new-hash"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
