package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.support.Mailpit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of password reset: the request sends a real email to the Mailpit container,
 * the token is read from that email and sent back to {@code POST /api/v1/auth/password-reset/confirm}.
 * <p>
 * Sign-up also sends a verification email, so every account receives that one first.
 */
@Import({TestcontainersConfiguration.class, Mailpit.class})
@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetApiTests {

    private static final String PASSWORD = "password123";

    private static final String NEW_PASSWORD = "brand-new-password";

    private static final String RESET_LINK = "http://localhost:3000/reset-password?token=";

    private static final String INVALID_TOKEN_TITLE = "Invalid password reset token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void fullFlowReplacesPasswordSoOnlyTheNewOneWorks() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        requestReset(email).andExpect(status().isAccepted());
        String token = awaitResetToken(email, 2);

        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());

        login(email, PASSWORD).andExpect(status().isUnauthorized());
        login(email, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void resetEmailGreetsUserAndContainsLinkAndExpiry() throws Exception {
        String email = uniqueEmail();
        signUp(email, "Alice Liddell");

        requestReset(email).andExpect(status().isAccepted());
        awaitEmailCount(email, 2);

        String text = mailpit.latestTextTo(email);

        assertThat(text).contains("Hello Alice Liddell,");
        assertThat(text).containsPattern("http://localhost:3000/reset-password\\?token=[A-Za-z0-9_-]{43}(\\s|$)");
        assertThat(text).contains("The link expires at ");
    }

    @Test
    void requestMatchesEmailIgnoringCase() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        requestReset(email.toUpperCase()).andExpect(status().isAccepted());

        confirm(awaitResetToken(email, 2), NEW_PASSWORD).andExpect(status().isNoContent());
        login(email, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void confirmIsPublicAndStoresArgon2idHash() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);

        requestReset(email).andExpect(status().isAccepted());

        confirm(awaitResetToken(email, 2), NEW_PASSWORD).andExpect(status().isNoContent());

        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?",
                String.class,
                userId
        );
        assertThat(passwordHash).startsWith("{argon2id}$argon2id$").doesNotContain(NEW_PASSWORD);
    }

    @Test
    void requestForUnknownEmailReturnsAcceptedAndSendsNoEmail() throws Exception {
        String email = uniqueEmail();

        requestReset(email).andExpect(status().isAccepted());

        // Give a wrongly sent email the time to arrive before asserting it never did
        Thread.sleep(500);

        assertThat(mailpit.countTo(email)).isZero();
    }

    @Test
    void requestForDisabledAccountReturnsAcceptedAndSendsNoEmailNorToken() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);
        mailpit.latestTextTo(email);

        adminPost("/api/v1/users/{id}/disable", userId).andExpect(status().isNoContent());

        requestReset(email).andExpect(status().isAccepted());

        Thread.sleep(500);

        assertThat(mailpit.countTo(email)).isEqualTo(1);
        assertThat(tokenRows(userId)).isEmpty();
    }

    @Test
    void tokenIsSingleUse() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);

        requestReset(email).andExpect(status().isAccepted());
        String token = awaitResetToken(email, 2);

        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());
        expectInvalidToken(confirm(token, "yet-another-password"));

        login(email, NEW_PASSWORD).andExpect(status().isOk());
        login(email, "yet-another-password").andExpect(status().isUnauthorized());

        List<Map<String, Object>> rows = tokenRows(userId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("used_at")).isNotNull();
    }

    @Test
    void expiredTokenIsRejectedAndPasswordUnchanged() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);

        requestReset(email).andExpect(status().isAccepted());
        String token = awaitResetToken(email, 2);

        jdbcTemplate.update(
                "UPDATE password_reset_tokens SET expires_at = now() - interval '1 second' WHERE user_id = ?",
                userId
        );

        expectInvalidToken(confirm(token, NEW_PASSWORD));

        login(email, PASSWORD).andExpect(status().isOk());
        login(email, NEW_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownTokenIsRejected() throws Exception {
        expectInvalidToken(confirm("unknown-" + UUID.randomUUID(), NEW_PASSWORD));
    }

    @Test
    void secondRequestInvalidatesFirstLink() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);

        requestReset(email).andExpect(status().isAccepted());
        String firstToken = awaitResetToken(email, 2);

        requestReset(email).andExpect(status().isAccepted());
        String secondToken = awaitResetToken(email, 3);

        assertThat(secondToken).isNotEqualTo(firstToken);

        List<String> hashes = jdbcTemplate.queryForList(
                "SELECT token_hash FROM password_reset_tokens WHERE user_id = ?",
                String.class,
                userId
        );
        assertThat(hashes).containsExactly(sha256Hex(secondToken));

        expectInvalidToken(confirm(firstToken, NEW_PASSWORD));
        login(email, PASSWORD).andExpect(status().isOk());

        confirm(secondToken, NEW_PASSWORD).andExpect(status().isNoContent());
        login(email, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void storesOnlyTheHashOfTheTokenWithOneHourExpiry() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);

        requestReset(email).andExpect(status().isAccepted());
        String token = awaitResetToken(email, 2);

        List<Map<String, Object>> rows = tokenRows(userId);
        assertThat(rows).hasSize(1);

        Map<String, Object> row = rows.getFirst();

        assertThat(row.get("token_hash")).isEqualTo(sha256Hex(token));
        assertThat(row.get("used_at")).isNull();
        assertThat(row.values()).noneMatch(value -> value != null && value.toString().contains(token));

        Integer rawMatches = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens WHERE token_hash = ?",
                Integer.class,
                token
        );
        assertThat(rawMatches).isZero();

        Instant createdAt = ((Timestamp) row.get("created_at")).toInstant();
        Instant expiresAt = ((Timestamp) row.get("expires_at")).toInstant();
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void confirmRevokesExistingRefreshTokens() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        String refreshToken = jsonMapper
                .readTree(login(email, PASSWORD)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("refreshToken")
                .asString();

        requestReset(email).andExpect(status().isAccepted());
        confirm(awaitResetToken(email, 2), NEW_PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"refreshToken": "%s"}
                                        """.formatted(refreshToken))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unverifiedUserBecomesVerifiedAfterReset() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);

        assertThat(emailVerifiedAt(userId)).isNull();

        requestReset(email).andExpect(status().isAccepted());
        String token = awaitResetToken(email, 2);
        Instant before = Instant.now();

        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());

        Timestamp verifiedAt = emailVerifiedAt(userId);
        assertThat(verifiedAt).isNotNull();
        assertThat(verifiedAt.toInstant()).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    @Test
    void tokenOfSoftDeletedUserIsRejected() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);

        requestReset(email).andExpect(status().isAccepted());
        String token = awaitResetToken(email, 2);

        mockMvc.perform(delete("/api/v1/users/{id}", userId).with(admin()))
                .andExpect(status().isNoContent());

        expectInvalidToken(confirm(token, NEW_PASSWORD));

        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT password_hash, deleted_at FROM users WHERE id = ?",
                userId
        );
        assertThat(user.get("deleted_at")).isNotNull();
        assertThat(tokenRows(userId).getFirst().get("used_at")).isNull();
    }

    @Test
    void tokenOfAccountDisabledAfterRequestIsRejected() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);

        requestReset(email).andExpect(status().isAccepted());
        String token = awaitResetToken(email, 2);

        adminPost("/api/v1/users/{id}/disable", userId).andExpect(status().isNoContent());

        expectInvalidToken(confirm(token, NEW_PASSWORD));

        adminPost("/api/v1/users/{id}/enable", userId).andExpect(status().isNoContent());
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void requestWithInvalidEmailReturnsBadRequest() throws Exception {
        requestReset("not-an-email").andExpect(status().isBadRequest());
        // @Email rejects surrounding spaces before the service could trim them
        requestReset("  " + uniqueEmail() + " ").andExpect(status().isBadRequest());
        requestReset("").andExpect(status().isBadRequest());

        mockMvc.perform(
                        post("/api/v1/auth/password-reset/request")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirmWithInvalidPayloadReturnsBadRequestAndKeepsToken() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);

        requestReset(email).andExpect(status().isAccepted());
        String token = awaitResetToken(email, 2);

        confirm(token, "short77").andExpect(status().isBadRequest());
        confirm(token, "x".repeat(129)).andExpect(status().isBadRequest());
        confirm(token, "        ").andExpect(status().isBadRequest());

        confirm("", NEW_PASSWORD).andExpect(status().isBadRequest());
        confirm("   ", NEW_PASSWORD).andExpect(status().isBadRequest());
        mockMvc.perform(
                        post("/api/v1/auth/password-reset/confirm")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"newPassword": "%s"}
                                        """.formatted(NEW_PASSWORD))
                )
                .andExpect(status().isBadRequest());

        assertThat(tokenRows(userId).getFirst().get("used_at")).isNull();
        String minimal = "12345678";
        confirm(token, minimal).andExpect(status().isNoContent());
        login(email, minimal).andExpect(status().isOk());
    }

    @Test
    void confirmAcceptsMaximumPasswordLength() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        requestReset(email).andExpect(status().isAccepted());
        String longest = "p".repeat(128);

        confirm(awaitResetToken(email, 2), longest).andExpect(status().isNoContent());
        login(email, longest).andExpect(status().isOk());
    }

    private UUID signUp(String email) throws Exception {
        return signUp(email, "Reset test");
    }

    private UUID signUp(String email, String displayName) throws Exception {
        String location = mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email": "%s", "password": "%s", "displayName": "%s"}
                                        """.formatted(email, PASSWORD, displayName))
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password))
        );
    }

    private ResultActions requestReset(String email) throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s"}
                                """.formatted(email))
        );
    }

    private ResultActions confirm(String token, String newPassword) throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "newPassword": "%s"}
                                """.formatted(token, newPassword))
        );
    }

    private ResultActions adminPost(String uri, UUID id) throws Exception {
        return mockMvc.perform(post(uri, id).with(admin()));
    }

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors
                .jwt()
                .jwt(jwt -> jwt.claim("uid", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /**
     * Waits until the address has received {@code expectedCount} emails (the verification email included),
     * then returns the token of the latest one, which must be a reset link.
     */
    private String awaitResetToken(String email, int expectedCount) throws InterruptedException {
        awaitEmailCount(email, expectedCount);

        String text = mailpit.latestTextTo(email);

        assertThat(text).contains(RESET_LINK);

        return Mailpit.extractToken(text);
    }

    private void awaitEmailCount(String email, int expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);

        while (mailpit.countTo(email) < expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }

        assertThat(mailpit.countTo(email)).isEqualTo(expected);
    }

    private List<Map<String, Object>> tokenRows(UUID userId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM password_reset_tokens WHERE user_id = ?",
                userId
        );
    }

    private Timestamp emailVerifiedAt(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT email_verified_at FROM users WHERE id = ?",
                Timestamp.class,
                userId
        );
    }

    private static void expectInvalidToken(ResultActions result) throws Exception {
        result
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value(INVALID_TOKEN_TITLE))
                .andExpect(jsonPath("$.status").value(400));
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = MessageDigest
                .getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));

        return HexFormat.of().formatHex(digest);
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
