package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.security.JwtProperties;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
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

    /** Issues an access token from the user's current state in the database (used on login and on refresh). */
    public IssuedToken generate(
            User user
    ) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(
                jwtProperties.ttl()
        );

        var roles = user
                .getRoles()
                .stream()
                .map(role -> "ROLE_" + role.name())
                .sorted()
                .toList();

        JwtClaimsSet claims = JwtClaimsSet
                .builder()
                .issuer(jwtProperties.issuer())
                .subject(user.getEmail())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(
                        "uid",
                        user.getId().toString()
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