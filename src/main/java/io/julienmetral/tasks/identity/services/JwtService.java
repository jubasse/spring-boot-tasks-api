package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.security.JwtProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public JwtService(
            JwtEncoder jwtEncoder,
            JwtProperties jwtProperties
    ) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
    }

    public IssuedToken generate(
            Authentication authentication,
            UUID userId
    ) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(
                jwtProperties.ttl()
        );

        var roles = authentication
                .getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .filter(authority ->
                        authority.startsWith("ROLE_")
                )
                .toList();

        JwtClaimsSet claims = JwtClaimsSet
                .builder()
                .issuer(jwtProperties.issuer())
                .subject(authentication.getName())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(
                        "uid",
                        userId.toString()
                )
                .claim(
                        "roles",
                        roles
                )
                .build();

        String accessToken = jwtEncoder
                .encode(
                        JwtEncoderParameters.from(claims)
                )
                .getTokenValue();

        return new IssuedToken(
                accessToken,
                expiresAt
        );
    }

    public record IssuedToken(
            String value,
            Instant expiresAt
    ) {
    }
}