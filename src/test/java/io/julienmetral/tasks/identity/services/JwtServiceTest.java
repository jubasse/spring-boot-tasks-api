package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.security.JwtProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    private static final JwtProperties PROPERTIES =
            new JwtProperties("tasks-api-test", Duration.ofMinutes(15), "unused");

    @Mock
    private JwtEncoder jwtEncoder;

    private static User user(UUID id, UserRole... roles) {
        User user = new User();
        user.setId(id);
        user.setEmail("jane@example.com");
        user.setRoles(roles.length == 0 ? EnumSet.noneOf(UserRole.class) : EnumSet.of(roles[0], roles));
        return user;
    }

    @Test
    void generateBuildsExpectedClaims() {
        Jwt encoded = Jwt.withTokenValue("encoded-token").header("alg", "HS256").claim("x", "y").build();
        when(jwtEncoder.encode(any())).thenReturn(encoded);
        JwtService jwtService = new JwtService(jwtEncoder, PROPERTIES);
        UUID userId = UUID.randomUUID();

        Instant before = Instant.now();
        JwtService.IssuedToken token = jwtService.generate(user(userId, UserRole.USER, UserRole.ADMIN));
        Instant after = Instant.now();

        ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(jwtEncoder).encode(captor.capture());
        JwtClaimsSet claims = captor.getValue().getClaims();

        assertThat(token.value()).isEqualTo("encoded-token");
        assertThat(claims.getClaimAsString("iss")).isEqualTo("tasks-api-test");
        assertThat(claims.getSubject()).isEqualTo("jane@example.com");
        assertThat(claims.getClaimAsString("uid")).isEqualTo(userId.toString());
        assertThat(claims.<List<String>>getClaim("roles")).containsExactly("ROLE_ADMIN", "ROLE_USER");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getIssuedAt()).isBetween(before, after);
        assertThat(claims.getExpiresAt())
                .isEqualTo(claims.getIssuedAt().plus(Duration.ofMinutes(15)))
                .isEqualTo(token.expiresAt());
    }

    @Test
    void generateWithRealEncoderProducesDecodableToken() {
        var key = new SecretKeySpec(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        UUID userId = UUID.randomUUID();

        JwtService.IssuedToken token = new JwtService(encoder, PROPERTIES).generate(user(userId, UserRole.USER, UserRole.ADMIN));
        Jwt decoded = decoder.decode(token.value());

        assertThat(decoded.getSubject()).isEqualTo("jane@example.com");
        assertThat(decoded.getClaimAsString("uid")).isEqualTo(userId.toString());
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("ROLE_ADMIN", "ROLE_USER");
        assertThat(decoded.getExpiresAt()).isEqualTo(token.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void generateWithNoRolesEmitsEmptyRolesClaim() {
        when(jwtEncoder.encode(any())).thenReturn(
                Jwt.withTokenValue("t").header("alg", "HS256").claim("x", "y").build());

        new JwtService(jwtEncoder, PROPERTIES).generate(user(UUID.randomUUID()));

        ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(jwtEncoder).encode(captor.capture());
        assertThat(captor.getValue().getClaims().<List<String>>getClaim("roles")).isEmpty();
    }
}
