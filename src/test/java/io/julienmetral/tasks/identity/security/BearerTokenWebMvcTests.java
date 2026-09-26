package io.julienmetral.tasks.identity.security;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.services.UserService;
import io.julienmetral.tasks.support.WebLayerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real Authorization headers: the jwt() post-processor of the other web tests skips the JwtDecoder and the roles
// conversion, which are what this class checks
@WebLayerTest
class BearerTokenWebMvcTests {

    private static final String ISSUER = "tasks-api";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserService userService;

    @Test
    void protectedEndpointWithoutTokenReturnsUnauthorizedWithBearerChallenge() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        getUser(UUID.randomUUID(), "not-a-jwt")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        String token = encode(jwtEncoder, ISSUER, id, List.of("ROLE_USER"), Instant.now().plusSeconds(600));

        // Change a character in the middle of the signature (the last one may only carry padding bits).
        int index = token.length() - 10;
        char replacement = token.charAt(index) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, index) + replacement + token.substring(index + 1);

        getUser(id, tampered)
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        JwtEncoder foreignEncoder = NimbusJwtEncoder
                .withSecretKey(new SecretKeySpec(otherKey, "HmacSHA256"))
                .algorithm(MacAlgorithm.HS256)
                .build();

        getUser(id, encode(foreignEncoder, ISSUER, id, List.of("ROLE_ADMIN"), Instant.now().plusSeconds(600)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void tokenWithWrongIssuerIsRejected() throws Exception {
        UUID id = UUID.randomUUID();

        getUser(id, encode(jwtEncoder, "someone-else", id, List.of("ROLE_USER"), Instant.now().plusSeconds(600)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        UUID id = UUID.randomUUID();

        // Beyond the default 60s clock skew tolerated by JwtTimestampValidator.
        getUser(id, encode(jwtEncoder, ISSUER, id, List.of("ROLE_USER"), Instant.now().minusSeconds(600)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenWithoutRolesClaimCannotUseAdminEndpoints() throws Exception {
        UUID id = UUID.randomUUID();
        when(userService.findById(id)).thenReturn(account(id));
        String token = encode(jwtEncoder, ISSUER, id, List.of(), Instant.now().plusSeconds(600));

        getUser(id, token)
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/users/{id}/disable", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void rolesClaimIsReadWithoutAddingAPrefix() throws Exception {
        UUID other = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        String token = encode(jwtEncoder, ISSUER, admin, List.of("ROLE_ADMIN"), Instant.now().plusSeconds(600));

        mockMvc.perform(post("/api/v1/users/{id}/disable", other)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        verify(userService).disable(other);
    }

    private ResultActions getUser(UUID id, String token) throws Exception {
        return mockMvc.perform(get("/api/v1/users/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static User account(UUID id) {
        User user = new User();
        user.setId(id);
        user.setEmail(id + "@example.com");
        user.setDisplayName("Bearer test");
        return user;
    }

    private static String encode(
            JwtEncoder encoder,
            String issuer,
            UUID userId,
            List<String> roles,
            Instant expiresAt
    ) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("test")
                .issuedAt(expiresAt.minusSeconds(900))
                .expiresAt(expiresAt)
                .claim("uid", userId.toString())
                .claim("roles", roles)
                .build();

        return encoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
