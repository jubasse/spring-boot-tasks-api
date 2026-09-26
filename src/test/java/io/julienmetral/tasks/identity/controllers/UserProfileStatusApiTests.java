package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.services.EmailVerificationService;
import io.julienmetral.tasks.identity.services.UserService;
import io.julienmetral.tasks.support.Mailpit;
import org.junit.jupiter.api.Disabled;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code user_profiles.status} is a copy of the account state: after every transition made through the API, it must
 * match what {@code users} says.
 */
@Import({TestcontainersConfiguration.class, Mailpit.class})
@SpringBootTest
@AutoConfigureMockMvc
class UserProfileStatusApiTests {

    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private UserService userService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void signUpCreatesAnUnverifiedProfileSharingTheAccountId() throws Exception {
        String email = uniqueEmail();

        UUID id = signUp(email, "Grace Hopper");

        Map<String, Object> profile = profileRow(id);
        assertThat(profile.get("display_name")).isEqualTo("Grace Hopper");
        assertThat(profile.get("status")).isEqualTo("UNVERIFIED");
        assertThat(profile.get("avatar_media_id")).isNull();
        assertThat(profile.get("anonymized_at")).isNull();
        assertThat(profile.get("created_at")).isNotNull();
        assertProfileStatusMatchesAccount(id);
    }

    @Test
    void verifyingTheEmailMakesTheProfileActive() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email, "Verified");

        verifyEmail(email);

        assertThat(profileStatus(id)).isEqualTo("ACTIVE");
        assertProfileStatusMatchesAccount(id);
    }

    @Test
    void confirmingAPasswordResetMakesAnUnverifiedProfileActive() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email, "Reset");
        mailpit.latestTextTo(email);

        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted());
        String token = awaitSecondEmailToken(email);

        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(
                                Map.of("token", token, "newPassword", "brand-new-password"))))
                .andExpect(status().isNoContent());

        assertThat(profileStatus(id)).isEqualTo("ACTIVE");
        assertProfileStatusMatchesAccount(id);
    }

    @Test
    void disablingMakesTheProfileDisabledAndEnablingMakesItActiveAgain() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email, "Toggled");
        verifyEmail(email);

        adminPost("/api/v1/users/{id}/disable", id);

        assertThat(profileStatus(id)).isEqualTo("DISABLED");
        assertProfileStatusMatchesAccount(id);

        adminPost("/api/v1/users/{id}/enable", id);

        assertThat(profileStatus(id)).isEqualTo("ACTIVE");
        assertProfileStatusMatchesAccount(id);
    }

    @Test
    void enablingADisabledUnverifiedAccountMakesTheProfileUnverifiedAgain() throws Exception {
        UUID id = signUp(uniqueEmail(), "Never verified");

        adminPost("/api/v1/users/{id}/disable", id);
        assertThat(profileStatus(id)).isEqualTo("DISABLED");

        adminPost("/api/v1/users/{id}/enable", id);

        assertThat(profileStatus(id)).isEqualTo("UNVERIFIED");
        assertProfileStatusMatchesAccount(id);
    }

    @Test
    void deletingMakesTheProfileDeletedAndKeepsItsName() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email, "Soon gone");
        verifyEmail(email);

        mockMvc.perform(delete("/api/v1/users/{id}", id).with(admin()))
                .andExpect(status().isNoContent());

        Map<String, Object> profile = profileRow(id);
        assertThat(profile.get("status")).isEqualTo("DELETED");
        assertThat(profile.get("display_name")).isEqualTo("Soon gone");
        assertThat(profile.get("anonymized_at")).isNull();
        assertProfileStatusMatchesAccount(id);
    }

    @Test
    void deletingADisabledAccountMakesTheProfileDeleted() throws Exception {
        UUID id = signUp(uniqueEmail(), "Disabled then deleted");
        adminPost("/api/v1/users/{id}/disable", id);

        mockMvc.perform(delete("/api/v1/users/{id}", id).with(admin()))
                .andExpect(status().isNoContent());

        assertThat(profileStatus(id)).isEqualTo("DELETED");
        assertProfileStatusMatchesAccount(id);
    }

    @Test
    void updatingTheDisplayNameWritesTheProfileAndLeavesTheStatus() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email, "Before");
        verifyEmail(email);

        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"After\"}"))
                .andExpect(status().isOk());

        assertThat(profileRow(id).get("display_name")).isEqualTo("After");
        assertThat(profileStatus(id)).isEqualTo("ACTIVE");
    }

    @Test
    @Disabled("bug: User.syncProfileStatus writes a status computed from the transaction's own snapshot of the "
            + "account, without a lock: a verification that loaded the account before an admin disabled it commits "
            + "ACTIVE over DISABLED, so the profile shows an active user (photo visible, mentionable) whose account "
            + "is disabled")
    void verificationRacingADisableLeavesTheProfileStatusMatchingTheAccount() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email, "Racing");
        String token = mailpit.latestVerificationTokenFor(email);
        CountDownLatch verificationLoaded = new CountDownLatch(1);
        CountDownLatch disableCommitted = new CountDownLatch(1);

        // The verification runs in an outer transaction held open until the disable has committed
        CompletableFuture<Void> verification = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    emailVerificationService.verify(token);
                    verificationLoaded.countDown();
                    await(disableCommitted);
                }));
        assertThat(verificationLoaded.await(10, TimeUnit.SECONDS)).isTrue();
        userService.disable(id);
        disableCommitted.countDown();
        verification.get(10, TimeUnit.SECONDS);

        assertProfileStatusMatchesAccount(id);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void assertProfileStatusMatchesAccount(UUID id) {
        Map<String, Object> account = jdbcTemplate.queryForMap(
                "SELECT enabled, email_verified_at, deleted_at FROM users WHERE id = ?",
                id
        );
        String expected;

        if (account.get("deleted_at") != null) {
            expected = "DELETED";
        } else if (!(Boolean) account.get("enabled")) {
            expected = "DISABLED";
        } else if (account.get("email_verified_at") == null) {
            expected = "UNVERIFIED";
        } else {
            expected = "ACTIVE";
        }

        assertThat(profileStatus(id)).isEqualTo(expected);
    }

    private UUID signUp(String email, String displayName) throws Exception {
        String location = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(
                                Map.of("email", email, "password", PASSWORD, "displayName", displayName))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private void verifyEmail(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(
                                Map.of("token", mailpit.latestVerificationTokenFor(email)))))
                .andExpect(status().isNoContent());
    }

    // The verification email arrives first; the reset email is the second one
    private String awaitSecondEmailToken(String email) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));

        while (mailpit.countTo(email) < 2 && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }

        assertThat(mailpit.countTo(email)).isEqualTo(2);

        return Mailpit.extractToken(mailpit.latestTextTo(email));
    }

    private void adminPost(String uri, UUID id) throws Exception {
        mockMvc.perform(post(uri, id).with(admin()))
                .andExpect(status().isNoContent());
    }

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors
                .jwt()
                .jwt(jwt -> jwt.claim("uid", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private Map<String, Object> profileRow(UUID id) {
        return jdbcTemplate.queryForMap("SELECT * FROM user_profiles WHERE id = ?", id);
    }

    private String profileStatus(UUID id) {
        return jdbcTemplate.queryForObject("SELECT status FROM user_profiles WHERE id = ?", String.class, id);
    }

    private static String uniqueEmail() {
        return "profile-status-" + UUID.randomUUID() + "@example.com";
    }
}
