package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------- sign-up

    @Test
    void signUpCreatesUserAndReturnsLocationAndBody() throws Exception {
        String email = uniqueEmail();

        String location = mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody(email, "password123", "  Alice  "))
                )
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", notNullValue()))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.roles", hasSize(1)))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.emailVerifiedAt").doesNotExist())
                .andExpect(jsonPath("$.lastLoginAt").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        assertThat(location).startsWith("/api/v1/users/");
        UUID id = idFromLocation(location);

        User saved = userRepository.findById(id).orElseThrow();
        assertThat(saved.getEmail()).isEqualTo(email);
        assertThat(saved.getPasswordHash())
                .isNotEqualTo("password123")
                .startsWith("{argon2id}");
    }

    @Test
    void signUpNormalizesEmailToLowerCase() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody(email.toUpperCase(), "password123", "Bob"))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void signUpIgnoresClientSuppliedRolesAndEnabledFlag() throws Exception {
        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email": "%s", "password": "password123", "displayName": "Mallory",
                                         "roles": ["ADMIN"], "enabled": false}
                                        """.formatted(uniqueEmail()))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles", hasSize(1)))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void signUpDoesNotRequireAuthentication() throws Exception {
        // Also valid with a token: sign-up is permitAll.
        User user = createUser(UserRole.USER);

        mockMvc.perform(
                        post("/api/v1/users")
                                .with(as(user, UserRole.USER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody(uniqueEmail(), "password123", "Carol"))
                )
                .andExpect(status().isCreated());
    }

    @Test
    void signUpRejectsInvalidEmail() throws Exception {
        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody("not-an-email", "password123", "Dave"))
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void signUpRejectsShortPassword() throws Exception {
        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody(uniqueEmail(), "short", "Dave"))
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void signUpRejectsTooLongPassword() throws Exception {
        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody(uniqueEmail(), "p".repeat(129), "Dave"))
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void signUpAcceptsPasswordAtDeclaredMaximumLength() throws Exception {
        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody(uniqueEmail(), "p".repeat(128), "Dave"))
                )
                .andExpect(status().isCreated());
    }

    @Test
    void signUpRejectsBlankDisplayName() throws Exception {
        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody(uniqueEmail(), "password123", "   "))
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void signUpRejectsMissingFields() throws Exception {
        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void signUpRejectsMalformedJson() throws Exception {
        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\": ")
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void signUpWithExistingEmailReturnsConflict() throws Exception {
        String email = uniqueEmail();
        signUp(email, "password123", "First");

        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody(email, "password456", "Second"))
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("User email already exists"));
    }

    @Test
    void signUpWithExistingEmailInDifferentCaseReturnsConflict() throws Exception {
        String email = uniqueEmail();
        signUp(email, "password123", "First");

        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody(email.toUpperCase(), "password456", "Second"))
                )
                .andExpect(status().isConflict());

        assertThat(
                jdbcTemplate.queryForObject(
                        "select count(*) from users where lower(email) = ?",
                        Long.class,
                        email
                )
        ).isEqualTo(1L);
    }

    @Test
    void signUpWithEmailOfSoftDeletedUserReturnsConflict() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        String email = uniqueEmail();
        UUID id = signUp(email, "password123", "Deleted");

        mockMvc.perform(delete("/api/v1/users/{id}", id).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNoContent());

        // users_emailUQ still covers the soft-deleted row: the email stays taken, with a clean 409
        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody(email.toUpperCase(), "password123", "Again"))
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("User email already exists"));
    }

    // ---------------------------------------------------------------- GET /{id}

    @Test
    void getUserWithoutTokenReturnsUnauthorized() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(get("/api/v1/users/{id}", user.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCanReadSelf() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(get("/api/v1/users/{id}", user.getId()).with(as(user, UserRole.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("USER")));
    }

    @Test
    void userCannotReadAnotherUser() throws Exception {
        User user = createUser(UserRole.USER);
        User other = createUser(UserRole.USER);

        mockMvc.perform(get("/api/v1/users/{id}", other.getId()).with(as(user, UserRole.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void userWithoutUidClaimCannotReadAnyUser() throws Exception {
        User other = createUser(UserRole.USER);

        mockMvc.perform(
                        get("/api/v1/users/{id}", other.getId())
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void userReadingUnknownIdGetsForbiddenNotNotFound() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(get("/api/v1/users/{id}", UUID.randomUUID()).with(as(user, UserRole.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadAnotherUser() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User other = createUser(UserRole.USER);

        mockMvc.perform(get("/api/v1/users/{id}", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(other.getId().toString()));
    }

    @Test
    void adminReadingUnknownUserGetsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(get("/api/v1/users/{id}", UUID.randomUUID()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("User not found"));
    }

    @Test
    void getUserWithInvalidUuidReturnsBadRequest() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(get("/api/v1/users/{id}", "not-a-uuid").with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- PATCH /{id}

    @Test
    void userCanUpdateOwnDisplayName() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(
                        patch("/api/v1/users/{id}", user.getId())
                                .with(as(user, UserRole.USER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"displayName": "  Renamed  "}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Renamed"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getDisplayName())
                .isEqualTo("Renamed");
    }

    @Test
    void userCannotUpdateAnotherUser() throws Exception {
        User user = createUser(UserRole.USER);
        User other = createUser(UserRole.USER);

        mockMvc.perform(
                        patch("/api/v1/users/{id}", other.getId())
                                .with(as(user, UserRole.USER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"displayName": "Hacked"}
                                        """)
                )
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(other.getId()).orElseThrow().getDisplayName())
                .isEqualTo(other.getDisplayName());
    }

    @Test
    void adminCanUpdateAnotherUser() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User other = createUser(UserRole.USER);

        mockMvc.perform(
                        patch("/api/v1/users/{id}", other.getId())
                                .with(as(admin, UserRole.ADMIN))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"displayName": "Set by admin"}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Set by admin"));
    }

    @Test
    void adminUpdatingUnknownUserGetsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(
                        patch("/api/v1/users/{id}", UUID.randomUUID())
                                .with(as(admin, UserRole.ADMIN))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"displayName": "Nobody"}
                                        """)
                )
                .andExpect(status().isNotFound());
    }

    @Test
    void updateRejectsTooLongDisplayName() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(
                        patch("/api/v1/users/{id}", user.getId())
                                .with(as(user, UserRole.USER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"displayName": "%s"}
                                        """.formatted("x".repeat(256)))
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateWithoutTokenReturnsUnauthorized() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(
                        patch("/api/v1/users/{id}", user.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"displayName": "Anon"}
                                        """)
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void partialUpdateWithoutDisplayNameKeepsCurrentValue() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(
                        patch("/api/v1/users/{id}", user.getId())
                                .with(as(user, UserRole.USER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value(user.getDisplayName()));
    }

    @Test
    void updateWithBlankDisplayNameKeepsCurrentValue() throws Exception {
        User user = createUser(UserRole.USER);

        mockMvc.perform(
                        patch("/api/v1/users/{id}", user.getId())
                                .with(as(user, UserRole.USER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"displayName": "   "}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value(user.getDisplayName()));
    }

    // ---------------------------------------------------------------- enable / disable

    @Test
    void userCannotDisableOrEnableUsersIncludingSelf() throws Exception {
        User user = createUser(UserRole.USER);
        User other = createUser(UserRole.USER);

        mockMvc.perform(post("/api/v1/users/{id}/disable", other.getId()).with(as(user, UserRole.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/users/{id}/disable", user.getId()).with(as(user, UserRole.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/users/{id}/enable", user.getId()).with(as(user, UserRole.USER)))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(other.getId()).orElseThrow().isEnabled()).isTrue();
        assertThat(userRepository.findById(user.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void adminCanDisableAndReEnableUser() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User other = createUser(UserRole.USER);

        mockMvc.perform(post("/api/v1/users/{id}/disable", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/{id}", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(post("/api/v1/users/{id}/enable", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/{id}", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void enableAndDisableAreIdempotent() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User other = createUser(UserRole.USER);

        mockMvc.perform(post("/api/v1/users/{id}/enable", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/users/{id}/disable", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/users/{id}/disable", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(other.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void adminEnablingOrDisablingUnknownUserGetsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(post("/api/v1/users/{id}/enable", UUID.randomUUID()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/users/{id}/disable", UUID.randomUUID()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void enableAndDisableWithoutTokenReturnUnauthorized() throws Exception {
        User other = createUser(UserRole.USER);

        mockMvc.perform(post("/api/v1/users/{id}/enable", other.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/users/{id}/disable", other.getId()))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- DELETE /{id}

    @Test
    void userCannotDeleteUsersIncludingSelf() throws Exception {
        User user = createUser(UserRole.USER);
        User other = createUser(UserRole.USER);

        mockMvc.perform(delete("/api/v1/users/{id}", other.getId()).with(as(user, UserRole.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/users/{id}", user.getId()).with(as(user, UserRole.USER)))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(other.getId())).isPresent();
        assertThat(userRepository.findById(user.getId())).isPresent();
    }

    @Test
    void deleteWithoutTokenReturnsUnauthorized() throws Exception {
        User other = createUser(UserRole.USER);

        mockMvc.perform(delete("/api/v1/users/{id}", other.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminDeleteIsSoftDelete() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User other = createUser(UserRole.USER);

        mockMvc.perform(delete("/api/v1/users/{id}", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/{id}", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNotFound());

        assertThat(userRepository.findById(other.getId())).isEmpty();

        // The row is still there, only flagged with deleted_at.
        Timestamp deletedAt = jdbcTemplate.queryForObject(
                "select deleted_at from users where id = ?",
                Timestamp.class,
                other.getId()
        );
        assertThat(deletedAt).isNotNull();
    }

    @Test
    void deletedUserCannotReadSelfWithStillValidToken() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User other = createUser(UserRole.USER);

        mockMvc.perform(delete("/api/v1/users/{id}", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/{id}", other.getId()).with(as(other, UserRole.USER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void operationsOnSoftDeletedUserReturnNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        User other = createUser(UserRole.USER);

        mockMvc.perform(delete("/api/v1/users/{id}", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/users/{id}", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/users/{id}/enable", other.getId()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        patch("/api/v1/users/{id}", other.getId())
                                .with(as(admin, UserRole.ADMIN))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"displayName": "Ghost"}
                                        """)
                )
                .andExpect(status().isNotFound());
    }

    @Test
    void adminDeletingUnknownUserGetsNotFound() throws Exception {
        User admin = createUser(UserRole.ADMIN);

        mockMvc.perform(delete("/api/v1/users/{id}", UUID.randomUUID()).with(as(admin, UserRole.ADMIN)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private UUID signUp(String email, String password, String displayName) throws Exception {
        String location = mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signUpBody(email, password, displayName))
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        return idFromLocation(location);
    }

    private static String signUpBody(String email, String password, String displayName) {
        return """
                {"email": "%s", "password": "%s", "displayName": "%s"}
                """.formatted(email, password, displayName);
    }

    private static UUID idFromLocation(String location) {
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private User createUser(UserRole role) {
        User user = new User();

        user.setEmail(uniqueEmail());
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setDisplayName("Test " + role);
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    private RequestPostProcessor as(User user, UserRole role) {
        return jwt()
                .jwt(token -> token.claim("uid", user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
