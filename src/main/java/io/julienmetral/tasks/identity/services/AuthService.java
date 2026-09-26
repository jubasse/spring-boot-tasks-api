package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.dtos.AuthResponseDto;
import io.julienmetral.tasks.identity.dtos.LoginRequestDto;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.exceptions.InvalidCredentialsException;
import io.julienmetral.tasks.identity.exceptions.InvalidRefreshTokenException;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.services.RefreshTokenService.IssuedRefreshToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            AuthenticationManager authenticationManager,
            UserRepository userRepository,
            JwtService jwtService,
            RefreshTokenService refreshTokenService
    ) {
        this.authenticationManager =
                authenticationManager;

        this.userRepository =
                userRepository;

        this.jwtService =
                jwtService;

        this.refreshTokenService =
                refreshTokenService;
    }

    @Transactional
    public AuthResponseDto login(
            LoginRequestDto dto
    ) {
        Authentication authentication;

        try {
            authentication =
                    authenticationManager.authenticate(
                            UsernamePasswordAuthenticationToken
                                    .unauthenticated(
                                            dto.email(),
                                            dto.password()
                                    )
                    );
        } catch (AuthenticationException exception) {
            throw new InvalidCredentialsException();
        }

        var user = userRepository
                .findByEmailIgnoreCase(
                        authentication.getName()
                )
                .orElseThrow();

        Instant now = Instant.now();

        user.setLastLoginAt(now);
        user.markActive(now);

        return tokens(
                user,
                refreshTokenService.issue(user)
        );
    }

    /**
     * Exchanges a refresh token for a new access token and a rotated refresh token.
     * <p>
     * An invalid token must not roll back the family revocation done by {@link RefreshTokenService#rotate}.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public AuthResponseDto refresh(
            String refreshToken
    ) {
        var rotated = refreshTokenService.rotate(
                refreshToken
        );

        // A client that keeps refreshing is in use even if its user never types the password again
        rotated.user().markActive(Instant.now());

        return tokens(
                rotated.user(),
                rotated.refreshToken()
        );
    }

    @Transactional
    public void logout(
            String refreshToken
    ) {
        refreshTokenService.revoke(
                refreshToken
        );
    }

    private AuthResponseDto tokens(
            User user,
            IssuedRefreshToken refreshToken
    ) {
        var accessToken = jwtService.generate(
                user
        );

        return new AuthResponseDto(
                accessToken.value(),
                "Bearer",
                accessToken.expiresAt(),
                refreshToken.value(),
                refreshToken.expiresAt()
        );
    }
}
