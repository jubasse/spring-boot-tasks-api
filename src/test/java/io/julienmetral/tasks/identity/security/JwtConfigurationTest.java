package io.julienmetral.tasks.identity.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigurationTest {

    private static final String ISSUER = "tasks-api-test";

    private final JwtConfiguration configuration = new JwtConfiguration();

    private static JwtProperties properties(int secretBytes) {
        byte[] secret = new byte[secretBytes];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) (i + 1);
        }
        return new JwtProperties(ISSUER, Duration.ofMinutes(15), Base64.getEncoder().encodeToString(secret));
    }

    private static JwtClaimsSet claims(String issuer) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("jane@example.com")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("uid", "00000000-0000-0000-0000-000000000001")
                .claim("roles", List.of("ROLE_USER"))
                .build();
    }

    private static String encode(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void secretShorterThan32BytesIsRejected() {
        assertThatThrownBy(() -> configuration.jwtSecretKey(properties(31)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256 bits");
    }

    @Test
    void secretOfExactly32BytesIsAccepted() {
        SecretKey key = configuration.jwtSecretKey(properties(32));

        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(key.getEncoded()).hasSize(32);
    }

    @Test
    void encodeDecodeRoundTrip() {
        JwtProperties props = properties(32);
        SecretKey key = configuration.jwtSecretKey(props);
        JwtEncoder encoder = configuration.jwtEncoder(key);
        JwtDecoder decoder = configuration.jwtDecoder(key, props);

        Jwt decoded = decoder.decode(encode(encoder, claims(ISSUER)));

        assertThat(decoded.getSubject()).isEqualTo("jane@example.com");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(decoded.getClaimAsString("uid")).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("ROLE_USER");
        assertThat(decoded.getHeaders()).containsEntry("alg", "HS256");
    }

    @Test
    void decoderRejectsTokenWithWrongIssuer() {
        JwtProperties props = properties(32);
        SecretKey key = configuration.jwtSecretKey(props);
        String token = encode(configuration.jwtEncoder(key), claims("someone-else"));
        JwtDecoder decoder = configuration.jwtDecoder(key, props);

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void decoderRejectsTokenSignedWithAnotherKey() {
        JwtProperties props = properties(32);
        SecretKey otherKey = configuration.jwtSecretKey(properties(48));
        String token = encode(configuration.jwtEncoder(otherKey), claims(ISSUER));
        JwtDecoder decoder = configuration.jwtDecoder(configuration.jwtSecretKey(props), props);

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(org.springframework.security.oauth2.jwt.BadJwtException.class);
    }
}
