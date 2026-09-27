package io.julienmetral.tasks.support;

import io.julienmetral.tasks.identity.repositories.AccountState;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** Authenticated callers for {@link WebLayerTest} classes, as the {@code jwt()} post-processor builds them. */
public final class WebCallers {

    private WebCallers() {
    }

    public static JwtRequestPostProcessor user(UUID id) {
        return withRole(id, "ROLE_USER");
    }

    public static JwtRequestPostProcessor admin(UUID id) {
        return withRole(id, "ROLE_ADMIN");
    }

    public static JwtRequestPostProcessor withoutUid() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /** Lets any caller with a {@code uid} claim through {@code ActiveUserAuthorizationManager}. */
    public static void everyAccountIsActive(UserRepository userRepository) {
        when(userRepository.findAccountStateById(any()))
                .thenReturn(Optional.of(new AccountState(true, Instant.parse("2026-01-01T00:00:00Z"))));
    }

    private static JwtRequestPostProcessor withRole(UUID id, String role) {
        return jwt()
                .jwt(token -> token.claim("uid", id.toString()))
                .authorities(new SimpleGrantedAuthority(role));
    }
}
