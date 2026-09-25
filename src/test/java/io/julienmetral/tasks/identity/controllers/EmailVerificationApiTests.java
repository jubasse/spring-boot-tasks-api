package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.support.Mailpit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of email verification: sign-up sends a real email to the Mailpit container,
 * the token is read from that email and sent back to {@code POST /api/v1/auth/verify-email}.
 */
@Import({TestcontainersConfiguration.class, Mailpit.class})
@SpringBootTest
@AutoConfigureMockMvc
class EmailVerificationApiTests {

    private static final String PASSWORD = "password123";

    private static final String DISPLAY_NAME = "Verification test";

    private static final String INVALID_TOKEN_TITLE = "Invalid email verification token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Value("${mailpit.api-url}")
    private String mailpitApiUrl;

    // ---------------------------------------------------------------- sign-up email

    @Test
    void signUpSendsVerificationEmailToLowerCasedAddressGreetingDisplayName() throws Exception {
        String localPart = UUID.randomUUID().toString();
        String mixedCaseEmail = "User." + localPart + "@Example.COM";
        String email = mixedCaseEmail.toLowerCase();

        signUp(mixedCaseEmail, "  Alice Liddell  ");

        String text = mailpit.latestTextTo(email);
        JsonNode message = latestMessageTo(email);

        assertThat(message.path("Subject").asString()).isEqualTo("Verify your email address");
        assertThat(message.path("To")).hasSize(1);
        assertThat(message.path("To").get(0).path("Address").asString()).isEqualTo(email);
        assertThat(text).contains("Hello Alice Liddell,");
        assertThat(text).containsPattern("http://localhost:3000/verify-email\\?token=[A-Za-z0-9_-]{43}(\\s|$)");
        assertThat(mailpit.countTo(email)).isEqualTo(1);
    }

    @Test
    void signUpStoresOnlyTheHashOfTheTokenWithTwentyFourHourExpiry() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);
        String token = mailpit.latestVerificationTokenFor(email);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM email_verification_tokens WHERE user_id = ?",
                userId
        );

        assertThat(rows).hasSize(1);

        Map<String, Object> row = rows.getFirst();

        assertThat(row.get("token_hash")).isEqualTo(sha256Hex(token));
        assertThat(row.get("used_at")).isNull();
        // The raw token appears in no column
        assertThat(row.values()).noneMatch(value -> value != null && value.toString().contains(token));

        Integer rawMatches = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_verification_tokens WHERE token_hash = ?",
                Integer.class,
                token
        );
        assertThat(rawMatches).isZero();

        Instant createdAt = ((Timestamp) row.get("created_at")).toInstant();
        Instant expiresAt = ((Timestamp) row.get("expires_at")).toInstant();
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void failedSignUpWithDuplicateEmailSendsNoEmail() throws Exception {
        String email = uniqueEmail();
        signUp(email);
        mailpit.latestTextTo(email);

        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody(email.toUpperCase(), "Duplicate"))
                )
                .andExpect(status().isConflict());

        // Give a wrongly sent email the time to arrive before asserting it never did
        Thread.sleep(500);

        assertThat(mailpit.countTo(email)).isEqualTo(1);
        assertThat(mailpit.latestTextTo(email)).doesNotContain("Duplicate");
    }

    // ---------------------------------------------------------------- verify

    @Test
    void verifyWithTokenFromEmailMarksUserVerifiedAndTokenUsed() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);
        String accessToken = login(email);

        getUser(userId, accessToken)
                .andExpect(jsonPath("$.emailVerifiedAt").doesNotExist());

        Instant before = Instant.now();

        verify(mailpit.latestVerificationTokenFor(email))
                .andExpect(status().isNoContent());

        String verifiedAt = verifiedAt(userId, accessToken);
        assertThat(verifiedAt).isNotNull();
        assertThat(Instant.parse(verifiedAt)).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));

        Timestamp usedAt = jdbcTemplate.queryForObject(
                "SELECT used_at FROM email_verification_tokens WHERE user_id = ?",
                Timestamp.class,
                userId
        );
        assertThat(usedAt).isNotNull();
    }

    @Test
    void verifyIsPublic() throws Exception {
        String email = uniqueEmail();
        signUp(email);

        // No Authorization header at all
        mockMvc.perform(
                        post("/api/v1/auth/verify-email")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(verifyBody(mailpit.latestVerificationTokenFor(email)))
                )
                .andExpect(status().isNoContent());
    }

    @Test
    void verifyingTwiceWithSameTokenFailsAndKeepsFirstVerificationDate() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);
        String accessToken = login(email);
        String token = mailpit.latestVerificationTokenFor(email);

        verify(token).andExpect(status().isNoContent());
        String firstVerifiedAt = verifiedAt(userId, accessToken);

        expectInvalidToken(verify(token));

        assertThat(verifiedAt(userId, accessToken)).isEqualTo(firstVerifiedAt);
    }

    @Test
    void verifyWithUnknownTokenReturnsBadRequest() throws Exception {
        expectInvalidToken(verify("unknown-" + UUID.randomUUID()));
    }

    @Test
    void verifyWithExpiredTokenReturnsBadRequestAndLeavesUserUnverified() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);
        String token = mailpit.latestVerificationTokenFor(email);

        jdbcTemplate.update(
                "UPDATE email_verification_tokens SET expires_at = now() - interval '1 second' WHERE user_id = ?",
                userId
        );

        expectInvalidToken(verify(token));

        assertThat(verifiedAt(userId, login(email))).isNull();
    }

    @Test
    void verifyWithBlankOrMissingTokenReturnsBadRequest() throws Exception {
        verify("").andExpect(status().isBadRequest());
        verify("   ").andExpect(status().isBadRequest());

        mockMvc.perform(
                        post("/api/v1/auth/verify-email")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        post("/api/v1/auth/verify-email")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"token": null}
                                        """)
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyWithTokenOfSoftDeletedUserReturnsBadRequest() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);
        String token = mailpit.latestVerificationTokenFor(email);

        mockMvc.perform(
                        delete("/api/v1/users/{id}", userId)
                                .with(jwt()
                                        .jwt(jwt -> jwt.claim("uid", UUID.randomUUID().toString()))
                                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                )
                .andExpect(status().isNoContent());

        expectInvalidToken(verify(token));

        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT email_verified_at, deleted_at FROM users WHERE id = ?",
                userId
        );
        assertThat(user.get("deleted_at")).isNotNull();
        assertThat(user.get("email_verified_at")).isNull();
    }

    // ---------------------------------------------------------------- login

    @Test
    void unverifiedUserCanLogIn() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);

        String accessToken = login(email);

        assertThat(verifiedAt(userId, accessToken)).isNull();
    }

    // ---------------------------------------------------------------- resend

    @Test
    void resendSendsNewEmailAndInvalidatesPreviousLink() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);
        String accessToken = login(email);
        String firstToken = mailpit.latestVerificationTokenFor(email);

        resend(accessToken).andExpect(status().isNoContent());

        awaitEmailCount(email, 2);
        String secondToken = mailpit.latestVerificationTokenFor(email);

        assertThat(secondToken).isNotEqualTo(firstToken);
        assertThat(latestMessageTo(email).path("Subject").asString()).isEqualTo("Verify your email address");

        // Only the new token remains, stored as its hash
        List<String> hashes = jdbcTemplate.queryForList(
                "SELECT token_hash FROM email_verification_tokens WHERE user_id = ?",
                String.class,
                userId
        );
        assertThat(hashes).containsExactly(sha256Hex(secondToken));

        expectInvalidToken(verify(firstToken));
        assertThat(verifiedAt(userId, accessToken)).isNull();

        verify(secondToken).andExpect(status().isNoContent());
        assertThat(verifiedAt(userId, accessToken)).isNotNull();
    }

    @Test
    void resendAfterExpiryIssuesWorkingLink() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);
        String accessToken = login(email);
        mailpit.latestTextTo(email);

        jdbcTemplate.update(
                "UPDATE email_verification_tokens SET expires_at = now() - interval '1 hour' WHERE user_id = ?",
                userId
        );

        resend(accessToken).andExpect(status().isNoContent());
        awaitEmailCount(email, 2);

        verify(mailpit.latestVerificationTokenFor(email)).andExpect(status().isNoContent());
        assertThat(verifiedAt(userId, accessToken)).isNotNull();
    }

    @Test
    void resendWhenAlreadyVerifiedReturnsConflictAndSendsNoEmail() throws Exception {
        String email = uniqueEmail();
        signUp(email);
        String accessToken = login(email);

        verify(mailpit.latestVerificationTokenFor(email)).andExpect(status().isNoContent());

        resend(accessToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Email already verified"))
                .andExpect(jsonPath("$.status").value(409));

        Thread.sleep(500);
        assertThat(mailpit.countTo(email)).isEqualTo(1);
    }

    @Test
    void resendWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email/resend"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resendUsesUserFromJwtUidClaim() throws Exception {
        String email = uniqueEmail();
        UUID userId = signUp(email);
        mailpit.latestTextTo(email);

        mockMvc.perform(
                        post("/api/v1/auth/verify-email/resend")
                                .with(jwt()
                                        .jwt(jwt -> jwt.claim("uid", userId.toString()))
                                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                )
                .andExpect(status().isNoContent());

        awaitEmailCount(email, 2);
    }

    // ---------------------------------------------------------------- helpers

    private UUID signUp(String email) throws Exception {
        return signUp(email, DISPLAY_NAME);
    }

    private UUID signUp(String email, String displayName) throws Exception {
        String location = mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody(email, displayName))
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email": "%s", "password": "%s"}
                                        """.formatted(email, PASSWORD))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(body).get("accessToken").asString();
    }

    private ResultActions verify(String token) throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(token))
        );
    }

    private ResultActions resend(String accessToken) throws Exception {
        return mockMvc.perform(
                post("/api/v1/auth/verify-email/resend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        );
    }

    private ResultActions getUser(UUID id, String accessToken) throws Exception {
        return mockMvc.perform(
                        get("/api/v1/users/{id}", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                )
                .andExpect(status().isOk());
    }

    /** {@code emailVerifiedAt} as returned by {@code GET /api/v1/users/{id}}, or null when absent. */
    private String verifiedAt(UUID id, String accessToken) throws Exception {
        String body = getUser(id, accessToken)
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode value = jsonMapper.readTree(body).path("emailVerifiedAt");

        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static void expectInvalidToken(ResultActions result) throws Exception {
        result
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value(INVALID_TOKEN_TITLE))
                .andExpect(jsonPath("$.status").value(400));
    }

    private void awaitEmailCount(String email, int expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);

        while (mailpit.countTo(email) < expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }

        assertThat(mailpit.countTo(email)).isEqualTo(expected);
    }

    /** Full Mailpit message (headers, subject, recipients) of the latest email sent to this address. */
    private JsonNode latestMessageTo(String email) {
        RestClient client = RestClient.create(mailpitApiUrl);

        JsonNode search = jsonMapper.readTree(
                client.get()
                        .uri("/api/v1/search?query={query}", "to:\"" + email + "\"")
                        .retrieve()
                        .body(String.class)
        );

        String id = search.path("messages").get(0).path("ID").asString();

        return jsonMapper.readTree(
                client.get()
                        .uri("/api/v1/message/{id}", id)
                        .retrieve()
                        .body(String.class)
        );
    }

    private static String signUpBody(String email, String displayName) {
        return """
                {"email": "%s", "password": "%s", "displayName": "%s"}
                """.formatted(email, PASSWORD, displayName);
    }

    private static String verifyBody(String token) {
        return """
                {"token": "%s"}
                """.formatted(token);
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
