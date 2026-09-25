package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.dtos.AuthResponseDto;
import io.julienmetral.tasks.identity.dtos.LoginRequestDto;
import io.julienmetral.tasks.identity.exceptions.InvalidCredentialsException;
import io.julienmetral.tasks.identity.repositories.UserRepository;
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

    public AuthService(
            AuthenticationManager authenticationManager,
            UserRepository userRepository,
            JwtService jwtService
    ) {
        this.authenticationManager =
                authenticationManager;

        this.userRepository =
                userRepository;

        this.jwtService =
                jwtService;
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

        user.setLastLoginAt(
                Instant.now()
        );

        var token = jwtService.generate(
                authentication,
                user.getId()
        );

        return new AuthResponseDto(
                token.value(),
                "Bearer",
                token.expiresAt()
        );
    }
}