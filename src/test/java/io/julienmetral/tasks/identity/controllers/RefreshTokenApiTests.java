package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import io.julienmetral.tasks.identity.security.OpaqueTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of refresh tokens: {@code POST /api/v1/auth/login}, {@code /refresh} and {@code /logout}
 * run for real against PostgreSQL, and the database is inspected with {@link JdbcTemplate} to check hashing,
 * rotation, family revocation and to force expiry.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RefreshTokenApiTests {

    private static final String PASSWORD = "password123";

    // application.yaml: security.refresh-token.ttl = 30d
    private static final Duration REFRESH_TTL = Duration.ofDays(30);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------- login

    @Test
    void loginReturnsRefreshTokenExpiringInAboutThirtyDays() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        Instant before = Instant.now();
        JsonNode json = loginJson(email);
        Instant after = Instant.now();

        assertThat(json.get("accessToken").asString()).isNotBlank();
        assertThat(json.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(json.get("expiresAt").asString()).isNotBlank();
        assertThat(json.get("refreshToken").asString()).isNotBlank();

        Instant refreshExpiresAt = Instant.parse(json.get("refreshTokenExpiresAt").asString());

        assertThat(refreshExpiresAt).isBetween(
                before.plus(REFRESH_TTL).minusSeconds(5),
                after.plus(REFRESH_TTL).plusSeconds(5)
        );
    }

    @Test
    void refreshTokensAreStoredOnlyAsSha256Hashes() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);

        String loginToken = refreshTokenOf(loginJson(email));
        String rotatedToken = refreshTokenOf(refreshOk(loginToken));

        for (String raw : List.of(loginToken, rotatedToken)) {
            Map<String, Object> row = tokenRow(raw);

            assertThat(row.get("token_hash"))
                    .isEqualTo(OpaqueTokens.hash(raw))
                    .asString()
                    .matches("[0-9a-f]{64}");
            assertThat(row.get("user_id")).isEqualTo(userId);

            // The raw value appears nowhere in the table, in any column
            Integer rawMatches = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM refresh_tokens t WHERE strpos(t::text, ?) > 0",
                    Integer.class,
                    raw
            );

            assertThat(rawMatches).isZero();
        }
    }

    // ---------------------------------------------------------------- refresh: happy path

    @Test
    void refreshReturnsNewTokensAndRotatesRefreshTokenWithinSameFamily() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        JsonNode login = loginJson(email);
        String original = refreshTokenOf(login);

        Instant before = Instant.now();

        String body = refresh(original)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshTokenExpiresAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = jsonMapper.readTree(body);
        String rotated = refreshTokenOf(json);

        assertThat(rotated).isNotEqualTo(original);
        assertThat(json.get("accessToken").asString()).isNotEqualTo(login.get("accessToken").asString());
        assertThat(Instant.parse(json.get("refreshTokenExpiresAt").asString())).isBetween(
                before.plus(REFRESH_TTL).minusSeconds(5),
                Instant.now().plus(REFRESH_TTL).plusSeconds(5)
        );

        Map<String, Object> originalRow = tokenRow(original);
        Map<String, Object> rotatedRow = tokenRow(rotated);

        assertThat(originalRow.get("revoked_at")).isNotNull();
        assertThat(rotatedRow.get("revoked_at")).isNull();
        assertThat(rotatedRow.get("family_id")).isEqualTo(originalRow.get("family_id"));
    }

    @Test
    void consecutiveRefreshesChainAndEachReturnsADifferentToken() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        String first = refreshTokenOf(loginJson(email));
        String second = refreshTokenOf(refreshOk(first));
        String third = refreshTokenOf(refreshOk(second));

        assertThat(List.of(first, second, third)).doesNotHaveDuplicates();

        UUID family = familyOf(first);

        assertThat(familyOf(second)).isEqualTo(family);
        assertThat(familyOf(third)).isEqualTo(family);
        assertThat(revokedAt(first)).isNotNull();
        assertThat(revokedAt(second)).isNotNull();
        assertThat(revokedAt(third)).isNull();

        // The latest token keeps working
        refreshOk(third);
    }

    @Test
    void accessTokenFromRefreshWorksOnProtectedEndpoint() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email);

        JsonNode refreshed = refreshOk(refreshTokenOf(loginJson(email)));
        String accessToken = refreshed.get("accessToken").asString();

        var jwt = jwtDecoder.decode(accessToken);

        assertThat(jwt.getSubject()).isEqualTo(email);
        assertThat(jwt.getClaimAsString("uid")).isEqualTo(id.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_USER");

        mockMvc.perform(
                        get("/api/v1/users/{id}", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void refreshedAccessTokenCarriesCurrentRoles() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email);
        UUID otherId = signUp(uniqueEmail());

        JsonNode login = loginJson(email);

        assertThat(jwtDecoder.decode(login.get("accessToken").asString()).getClaimAsStringList("roles"))
                .containsExactly("ROLE_USER");

        // Promoted behind the application's back, after the login
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'ADMIN')", id);

        String accessToken = refreshOk(refreshTokenOf(login)).get("accessToken").asString();

        assertThat(jwtDecoder.decode(accessToken).getClaimAsStringList("roles"))
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");

        // Reading another user requires ROLE_ADMIN
        mockMvc.perform(
                        get("/api/v1/users/{id}", otherId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                )
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- refresh: reuse detection

    @Test
    void replayingUsedRefreshTokenRevokesWholeFamily() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        String first = refreshTokenOf(loginJson(email));
        String second = refreshTokenOf(refreshOk(first));

        expectInvalidRefreshToken(refresh(first));

        assertThat(revokedAt(second)).isNotNull();

        // The legitimate latest token of the family is now dead too
        expectInvalidRefreshToken(refresh(second));
    }

    @Test
    void reuseDetectionDoesNotAffectOtherFamiliesOfSameUser() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        String leaked = refreshTokenOf(loginJson(email));
        String otherSession = refreshTokenOf(loginJson(email));

        refreshOk(leaked);
        expectInvalidRefreshToken(refresh(leaked));

        refreshOk(otherSession);
    }

    // ---------------------------------------------------------------- refresh: invalid tokens

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        String token = refreshTokenOf(loginJson(email));

        jdbcTemplate.update(
                "UPDATE refresh_tokens SET expires_at = now() - interval '1 second' WHERE token_hash = ?",
                OpaqueTokens.hash(token)
        );

        expectInvalidRefreshToken(refresh(token));
    }

    @Test
    void unknownRefreshTokenIsRejected() throws Exception {
        expectInvalidRefreshToken(refresh(OpaqueTokens.generate()));
    }

    @Test
    void malformedRefreshTokenIsRejected() throws Exception {
        expectInvalidRefreshToken(refresh("not a real token !@#$%^&*()"));
    }

    @Test
    void accessTokenUsedAsRefreshTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        // A JWT is longer than 128 characters, so validation rejects it before the service sees it
        String accessToken = loginJson(email).get("accessToken").asString();

        refresh(accessToken).andExpect(status().isBadRequest());
    }

    @Test
    void blankOrMissingRefreshTokenIsBadRequest() throws Exception {
        for (String body : List.of("{}", "{\"refreshToken\": null}", "{\"refreshToken\": \"\"}", "{\"refreshToken\": \"   \"}")) {
            mockMvc.perform(
                            post("/api/v1/auth/refresh")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body)
                    )
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void oversizedRefreshTokenIsBadRequest() throws Exception {
        refresh("a".repeat(129)).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- refresh: user state

    @Test
    void disablingUserRevokesRefreshTokensEvenAfterReEnabling() throws Exception {
        String adminToken = adminAccessToken();
        String email = uniqueEmail();
        UUID id = signUp(email);

        String first = refreshTokenOf(loginJson(email));
        String second = refreshTokenOf(loginJson(email));

        mockMvc.perform(
                        post("/api/v1/users/{id}/disable", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                )
                .andExpect(status().isNoContent());

        assertThat(revokedAt(first)).isNotNull();
        assertThat(revokedAt(second)).isNotNull();

        expectInvalidRefreshToken(refresh(first));

        mockMvc.perform(
                        post("/api/v1/users/{id}/enable", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                )
                .andExpect(status().isNoContent());

        // Old sessions do not come back, a fresh login does work
        expectInvalidRefreshToken(refresh(second));
        refreshOk(refreshTokenOf(loginJson(email)));
    }

    @Test
    void userDisabledOutsideTheServiceCannotRefresh() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email);

        String token = refreshTokenOf(loginJson(email));

        // The token itself is still valid: only the user's state blocks the refresh
        jdbcTemplate.update("UPDATE users SET enabled = false WHERE id = ?", id);

        expectInvalidRefreshToken(refresh(token));
    }

    @Test
    void deletingUserRevokesRefreshTokens() throws Exception {
        String adminToken = adminAccessToken();
        String email = uniqueEmail();
        UUID id = signUp(email);

        String token = refreshTokenOf(loginJson(email));

        mockMvc.perform(
                        delete("/api/v1/users/{id}", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                )
                .andExpect(status().isNoContent());

        assertThat(revokedAt(token)).isNotNull();

        expectInvalidRefreshToken(refresh(token));
    }

    @Test
    void userSoftDeletedOutsideTheServiceCannotRefresh() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email);

        String token = refreshTokenOf(loginJson(email));

        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", id);

        expectInvalidRefreshToken(refresh(token));
    }

    // ---------------------------------------------------------------- logout

    @Test
    void logoutRevokesTokenAndItCanNoLongerBeRefreshed() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        String token = refreshTokenOf(loginJson(email));

        // Public endpoint: no Authorization header
        logout(token).andExpect(status().isNoContent());

        assertThat(revokedAt(token)).isNotNull();

        expectInvalidRefreshToken(refresh(token));
    }

    @Test
    void logoutRevokesWholeFamily() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        String first = refreshTokenOf(loginJson(email));
        String second = refreshTokenOf(refreshOk(first));

        // Logging out with the already-rotated token still ends the session
        logout(first).andExpect(status().isNoContent());

        assertThat(revokedAt(second)).isNotNull();

        expectInvalidRefreshToken(refresh(second));
    }

    @Test
    void logoutOfOneLoginDoesNotAffectAnotherLoginOfSameUser() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        String firstSession = refreshTokenOf(loginJson(email));
        String secondSession = refreshTokenOf(loginJson(email));

        assertThat(familyOf(firstSession)).isNotEqualTo(familyOf(secondSession));

        logout(firstSession).andExpect(status().isNoContent());

        expectInvalidRefreshToken(refresh(firstSession));
        assertThat(revokedAt(secondSession)).isNull();
        refreshOk(secondSession);
    }

    @Test
    void logoutAfterUserSoftDeletionStillReturnsNoContent() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email);

        String token = refreshTokenOf(loginJson(email));

        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", id);

        logout(token).andExpect(status().isNoContent());
    }

    @Test
    void logoutIsIdempotent() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        String token = refreshTokenOf(loginJson(email));

        logout(token).andExpect(status().isNoContent());
        logout(token).andExpect(status().isNoContent());
    }

    @Test
    void logoutWithUnknownTokenStillReturnsNoContent() throws Exception {
        logout(OpaqueTokens.generate()).andExpect(status().isNoContent());
        logout("garbage").andExpect(status().isNoContent());
    }

    @Test
    void logoutWithBlankOrMissingTokenIsBadRequest() throws Exception {
        for (String body : List.of("{}", "{\"refreshToken\": \"\"}", "{\"refreshToken\": \"  \"}")) {
            mockMvc.perform(
                            post("/api/v1/auth/logout")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body)
                    )
                    .andExpect(status().isBadRequest());
        }
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody(refreshToken))
        );
    }

    private JsonNode refreshOk(String refreshToken) throws Exception {
        String body = refresh(refreshToken)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(body);
    }

    private ResultActions logout(String refreshToken) throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody(refreshToken))
        );
    }

    private static void expectInvalidRefreshToken(ResultActions result) throws Exception {
        result
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Invalid refresh token"))
                .andExpect(jsonPath("$.status").value(401));
    }

    private String tokenBody(String refreshToken) {
        return jsonMapper.writeValueAsString(Map.of("refreshToken", refreshToken));
    }

    private UUID signUp(String email) throws Exception {
        String location = mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email": "%s", "password": "%s", "displayName": "Refresh test"}
                                        """.formatted(email, PASSWORD))
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private JsonNode loginJson(String email) throws Exception {
        return loginJson(email, PASSWORD);
    }

    private JsonNode loginJson(String email, String password) throws Exception {
        String body = mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(jsonMapper.writeValueAsString(Map.of("email", email, "password", password)))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(body);
    }

    private static String refreshTokenOf(JsonNode authResponse) {
        return authResponse.get("refreshToken").asString();
    }

    /** An admin created directly in the DB (password "password"), logged in through the API. */
    private String adminAccessToken() throws Exception {
        User admin = new User();

        admin.setEmail(uniqueEmail());
        admin.setPasswordHash(passwordEncoder.encode("password"));
        admin.setDisplayName("Refresh test admin");
        admin.setRoles(EnumSet.of(UserRole.USER, UserRole.ADMIN));

        userRepository.saveAndFlush(admin);

        return loginJson(admin.getEmail(), "password").get("accessToken").asString();
    }

    private Map<String, Object> tokenRow(String rawToken) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM refresh_tokens WHERE token_hash = ?",
                OpaqueTokens.hash(rawToken)
        );
    }

    private UUID familyOf(String rawToken) {
        return (UUID) tokenRow(rawToken).get("family_id");
    }

    private Object revokedAt(String rawToken) {
        return tokenRow(rawToken).get("revoked_at");
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
