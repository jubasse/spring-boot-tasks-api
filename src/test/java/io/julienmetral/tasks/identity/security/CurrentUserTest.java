package io.julienmetral.tasks.identity.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserTest {

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    static JwtAuthenticationToken jwtAuth(String uid) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("jane@example.com");
        if (uid != null) {
            builder.claim("uid", uid);
        }
        return new JwtAuthenticationToken(builder.build());
    }

    @Test
    void nullAuthenticationYieldsEmpty() {
        assertThat(currentUser.getId(null)).isEmpty();
    }

    @Test
    void nonJwtAuthenticationYieldsEmpty() {
        var auth = UsernamePasswordAuthenticationToken.authenticated("jane", null, java.util.List.of());

        assertThat(currentUser.getId(auth)).isEmpty();
    }

    @Test
    void missingUidClaimYieldsEmpty() {
        assertThat(currentUser.getId(jwtAuth(null))).isEmpty();
    }

    @Test
    void malformedUidClaimYieldsEmpty() {
        assertThat(currentUser.getId(jwtAuth("not-a-uuid"))).isEmpty();
    }

    @Test
    void validUidClaimYieldsUuid() {
        UUID id = UUID.randomUUID();

        assertThat(currentUser.getId(jwtAuth(id.toString()))).contains(id);
    }

    @Test
    void noArgGetIdReadsSecurityContext() {
        UUID id = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(jwtAuth(id.toString()));

        assertThat(currentUser.getId()).contains(id);
    }

    @Test
    void noArgGetIdWithEmptySecurityContextYieldsEmpty() {
        assertThat(currentUser.getId()).isEmpty();
    }
}
