package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.TestcontainersConfiguration;
import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.identity.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the login flow: tokens issued by {@code POST /api/v1/auth/login}
 * are sent back as real {@code Authorization: Bearer} headers, so JwtService, the
 * JwtEncoder/JwtDecoder from JwtConfiguration and the JwtAuthenticationConverter
 * all run for real (no {@code jwt()} post-processor).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTests {

    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    // ---------------------------------------------------------------- happy path

    @Test
    void signedUpUserCanLogInAndUseTokenOnProtectedEndpoint() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email);

        Instant before = Instant.now();

        String body = mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(email, PASSWORD))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = jsonMapper.readTree(body);
        String token = json.get("accessToken").asString();
        Instant expiresAt = Instant.parse(json.get("expiresAt").asString());

        // application.yaml: security.jwt.ttl = 15m
        assertThat(expiresAt).isBetween(
                before.plus(Duration.ofMinutes(15)).minusSeconds(5),
                Instant.now().plus(Duration.ofMinutes(15)).plusSeconds(5)
        );

        mockMvc.perform(
                        get("/api/v1/users/{id}", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void issuedTokenCarriesExpectedClaims() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email);

        var jwt = jwtDecoder.decode(login(email, PASSWORD));

        assertThat(jwt.getSubject()).isEqualTo(email);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("tasks-api");
        assertThat(jwt.getClaimAsString("uid")).isEqualTo(id.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_USER");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void userTokenCanUpdateSelfButNotOthersNorUseAdminEndpoints() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email);
        UUID otherId = signUp(uniqueEmail());
        String token = login(email, PASSWORD);

        mockMvc.perform(
                        patch("/api/v1/users/{id}", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"displayName": "Via real token"}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Via real token"));

        mockMvc.perform(
                        get("/api/v1/users/{id}", otherId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        post("/api/v1/users/{id}/disable", otherId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        delete("/api/v1/users/{id}", otherId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void adminTokenFromLoginGrantsAdminEndpoints() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        UUID otherId = signUp(uniqueEmail());

        String token = login(admin.getEmail(), "password");

        assertThat(jwtDecoder.decode(token).getClaimAsStringList("roles"))
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");

        mockMvc.perform(
                        get("/api/v1/users/{id}", otherId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("USER")));

        mockMvc.perform(
                        post("/api/v1/users/{id}/disable", otherId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        delete("/api/v1/users/{id}", otherId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isNoContent());
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email);

        String token = login(email.toUpperCase(), PASSWORD);

        assertThat(jwtDecoder.decode(token).getClaimAsString("uid")).isEqualTo(id.toString());
    }

    @Test
    void loginUpgradesLegacyBcryptHashToArgon2id() throws Exception {
        User user = createUser(UserRole.USER);
        user.setPasswordHash("{bcrypt}" + new BCryptPasswordEncoder().encode("password"));
        userRepository.saveAndFlush(user);

        login(user.getEmail(), "password");

        String upgradedHash = userRepository.findById(user.getId()).orElseThrow().getPasswordHash();
        assertThat(upgradedHash).startsWith("{argon2id}");
        assertThat(passwordEncoder.matches("password", upgradedHash)).isTrue();

        // The upgraded hash keeps working
        login(user.getEmail(), "password");
    }

    @Test
    void loginSetsLastLoginAt() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email);

        assertThat(userRepository.findById(id).orElseThrow().getLastLoginAt()).isNull();

        Instant before = Instant.now();
        String token = login(email, PASSWORD);

        Instant lastLoginAt = userRepository.findById(id).orElseThrow().getLastLoginAt();
        assertThat(lastLoginAt).isNotNull();
        assertThat(lastLoginAt).isAfterOrEqualTo(before.minusSeconds(1));

        mockMvc.perform(
                        get("/api/v1/users/{id}", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastLoginAt").isNotEmpty());
    }

    // ---------------------------------------------------------------- failures

    @Test
    void loginWithWrongPasswordReturnsUnauthorized() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email);

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(email, "wrong-password"))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        assertThat(userRepository.findById(id).orElseThrow().getLastLoginAt()).isNull();
    }

    @Test
    void loginWithUnknownEmailReturnsSameUnauthorizedResponse() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(uniqueEmail(), PASSWORD))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void disabledUserCannotLogInUntilReEnabled() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        String adminToken = login(admin.getEmail(), "password");
        String email = uniqueEmail();
        UUID id = signUp(email);

        mockMvc.perform(
                        post("/api/v1/users/{id}/disable", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                )
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(email, PASSWORD))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        assertThat(userRepository.findById(id).orElseThrow().getLastLoginAt()).isNull();

        mockMvc.perform(
                        post("/api/v1/users/{id}/enable", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                )
                .andExpect(status().isNoContent());

        login(email, PASSWORD);
    }

    @Test
    void softDeletedUserCannotLogIn() throws Exception {
        User admin = createUser(UserRole.ADMIN);
        String email = uniqueEmail();
        UUID id = signUp(email);

        mockMvc.perform(
                        delete("/api/v1/users/{id}", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login(admin.getEmail(), "password"))
                )
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(email, PASSWORD))
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsInvalidPayload() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody("not-an-email", PASSWORD))
                )
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(uniqueEmail(), ""))
                )
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- token validation

    @Test
    void protectedEndpointWithoutTokenReturnsUnauthorizedWithBearerChallenge() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        mockMvc.perform(
                        get("/api/v1/users/{id}", UUID.randomUUID())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        UUID id = signUp(email);
        String token = login(email, PASSWORD);

        // Change a character in the middle of the signature (the last one may only carry padding bits).
        int index = token.length() - 10;
        char replacement = token.charAt(index) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, index) + replacement + token.substring(index + 1);

        mockMvc.perform(
                        get("/api/v1/users/{id}", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered)
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        UUID id = signUp(uniqueEmail());

        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        JwtEncoder foreignEncoder = NimbusJwtEncoder
                .withSecretKey(new SecretKeySpec(otherKey, "HmacSHA256"))
                .algorithm(MacAlgorithm.HS256)
                .build();

        String forged = encode(foreignEncoder, "tasks-api", id, List.of("ROLE_ADMIN"), Instant.now().plusSeconds(600));

        mockMvc.perform(
                        get("/api/v1/users/{id}", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithWrongIssuerIsRejected() throws Exception {
        UUID id = signUp(uniqueEmail());

        String token = encode(jwtEncoder, "someone-else", id, List.of("ROLE_USER"), Instant.now().plusSeconds(600));

        mockMvc.perform(
                        get("/api/v1/users/{id}", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        UUID id = signUp(uniqueEmail());

        // Beyond the default 60s clock skew tolerated by JwtTimestampValidator.
        String token = encode(jwtEncoder, "tasks-api", id, List.of("ROLE_USER"), Instant.now().minusSeconds(600));

        mockMvc.perform(
                        get("/api/v1/users/{id}", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenWithoutRolesClaimCannotUseAdminEndpoints() throws Exception {
        UUID id = signUp(uniqueEmail());
        UUID otherId = signUp(uniqueEmail());

        String token = encode(jwtEncoder, "tasks-api", id, List.of(), Instant.now().plusSeconds(600));

        mockMvc.perform(
                        get("/api/v1/users/{id}", id)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/users/{id}/disable", otherId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private UUID signUp(String email) throws Exception {
        String location = mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email": "%s", "password": "%s", "displayName": "Auth test"}
                                        """.formatted(email, PASSWORD))
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody(email, password))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(body).get("accessToken").asString();
    }

    private static String loginBody(String email, String password) {
        return """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password);
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

    /** Created directly in the DB with the password "password", hashed by the application's encoder. */
    private User createUser(UserRole role) {
        User user = new User();

        user.setEmail(uniqueEmail());
        user.setPasswordHash(passwordEncoder.encode("password"));
        user.setDisplayName("Test " + role);
        user.setRoles(EnumSet.of(UserRole.USER, role));

        return userRepository.saveAndFlush(user);
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
