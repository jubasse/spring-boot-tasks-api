package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.dtos.AuthResponseDto;
import io.julienmetral.tasks.identity.dtos.LoginRequestDto;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.InvalidCredentialsException;
import io.julienmetral.tasks.identity.exceptions.InvalidRefreshTokenException;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthService authService;

    @Test
    void loginReturnsBearerTokenAndRecordsLastLogin() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setEmail("jane@example.com");

        Authentication authenticated = UsernamePasswordAuthenticationToken.authenticated(
                "jane@example.com", null, AuthorityUtils.createAuthorityList("ROLE_USER"));
        when(authenticationManager.authenticate(any())).thenReturn(authenticated);
        when(userRepository.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(user));
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        when(jwtService.generate(user))
                .thenReturn(new JwtService.IssuedToken("token-value", expiresAt));
        Instant refreshExpiresAt = Instant.parse("2030-02-01T00:00:00Z");
        when(refreshTokenService.issue(user))
                .thenReturn(new RefreshTokenService.IssuedRefreshToken("refresh-value", refreshExpiresAt));

        Instant before = Instant.now();
        AuthResponseDto response = authService.login(new LoginRequestDto("jane@example.com", "pw"));

        assertThat(response.accessToken()).isEqualTo("token-value");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        assertThat(response.refreshToken()).isEqualTo("refresh-value");
        assertThat(response.refreshTokenExpiresAt()).isEqualTo(refreshExpiresAt);
        assertThat(user.getLastLoginAt()).isBetween(before, Instant.now());

        ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(captor.capture());
        assertThat(captor.getValue().isAuthenticated()).isFalse();
        assertThat(captor.getValue().getPrincipal()).isEqualTo("jane@example.com");
        assertThat(captor.getValue().getCredentials()).isEqualTo("pw");
    }

    @Test
    void loginWithBadCredentialsThrowsInvalidCredentials() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("jane@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");

        verifyNoInteractions(userRepository, jwtService, refreshTokenService);
    }

    @Test
    void loginWithDisabledAccountThrowsInvalidCredentials() {
        when(authenticationManager.authenticate(any())).thenThrow(new DisabledException("disabled"));

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("jane@example.com", "pw")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshReturnsNewAccessTokenAndRotatedRefreshToken() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("jane@example.com");
        Instant refreshExpiresAt = Instant.parse("2030-02-01T00:00:00Z");
        when(refreshTokenService.rotate("old-refresh")).thenReturn(new RefreshTokenService.RotatedRefreshToken(
                user, new RefreshTokenService.IssuedRefreshToken("new-refresh", refreshExpiresAt)));
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        when(jwtService.generate(user)).thenReturn(new JwtService.IssuedToken("access-value", expiresAt));

        AuthResponseDto response = authService.refresh("old-refresh");

        assertThat(response.accessToken()).isEqualTo("access-value");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        assertThat(response.refreshTokenExpiresAt()).isEqualTo(refreshExpiresAt);
        // A refresh is not a login
        assertThat(user.getLastLoginAt()).isNull();
        verifyNoInteractions(authenticationManager, userRepository);
    }

    @Test
    void loginMarksTheUserActiveAndClearsTheInactivityWarning() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("jane@example.com");
        user.setLastActiveAt(Instant.parse("2020-01-01T00:00:00Z"));
        user.setInactivityWarnedAt(Instant.parse("2021-01-01T00:00:00Z"));

        when(authenticationManager.authenticate(any())).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                "jane@example.com", null, AuthorityUtils.createAuthorityList("ROLE_USER")));
        when(userRepository.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generate(user))
                .thenReturn(new JwtService.IssuedToken("token-value", Instant.parse("2030-01-01T00:00:00Z")));
        when(refreshTokenService.issue(user)).thenReturn(new RefreshTokenService.IssuedRefreshToken(
                "refresh-value", Instant.parse("2030-02-01T00:00:00Z")));

        Instant before = Instant.now();
        authService.login(new LoginRequestDto("jane@example.com", "pw"));

        assertThat(user.getLastActiveAt()).isBetween(before, Instant.now());
        assertThat(user.getLastActiveAt()).isEqualTo(user.getLastLoginAt());
        assertThat(user.getInactivityWarnedAt()).isNull();
    }

    @Test
    void refreshMarksTheUserActiveAndClearsTheInactivityWarning() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setLastActiveAt(Instant.parse("2020-01-01T00:00:00Z"));
        user.setInactivityWarnedAt(Instant.parse("2021-01-01T00:00:00Z"));

        when(refreshTokenService.rotate("old-refresh")).thenReturn(new RefreshTokenService.RotatedRefreshToken(
                user, new RefreshTokenService.IssuedRefreshToken("new-refresh", Instant.parse("2030-02-01T00:00:00Z"))));
        when(jwtService.generate(user))
                .thenReturn(new JwtService.IssuedToken("access-value", Instant.parse("2030-01-01T00:00:00Z")));

        Instant before = Instant.now();
        authService.refresh("old-refresh");

        assertThat(user.getLastActiveAt()).isBetween(before, Instant.now());
        assertThat(user.getInactivityWarnedAt()).isNull();
        assertThat(user.getLastLoginAt()).isNull();
    }

    @Test
    void refreshWithInvalidTokenPropagatesAndIssuesNoAccessToken() {
        when(refreshTokenService.rotate("bad")).thenThrow(new InvalidRefreshTokenException());

        assertThatThrownBy(() -> authService.refresh("bad")).isInstanceOf(InvalidRefreshTokenException.class);

        verifyNoInteractions(jwtService);
    }

    @Test
    void logoutRevokesRefreshToken() {
        authService.logout("refresh-value");

        verify(refreshTokenService).revoke("refresh-value");
        verifyNoInteractions(authenticationManager, userRepository, jwtService);
    }
}
