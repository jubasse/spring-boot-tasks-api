package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.identity.dtos.AuthResponseDto;
import io.julienmetral.tasks.identity.dtos.LoginRequestDto;
import io.julienmetral.tasks.identity.exceptions.EmailAlreadyVerifiedException;
import io.julienmetral.tasks.identity.exceptions.InvalidCredentialsException;
import io.julienmetral.tasks.identity.exceptions.InvalidEmailVerificationTokenException;
import io.julienmetral.tasks.identity.exceptions.InvalidPasswordResetTokenException;
import io.julienmetral.tasks.identity.exceptions.InvalidRefreshTokenException;
import io.julienmetral.tasks.identity.services.AuthService;
import io.julienmetral.tasks.identity.services.EmailVerificationService;
import io.julienmetral.tasks.identity.services.PasswordResetService;
import io.julienmetral.tasks.ratelimit.exceptions.RateLimitExceededException;
import io.julienmetral.tasks.ratelimit.services.RateLimiter;
import io.julienmetral.tasks.support.WebLayerTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.julienmetral.tasks.support.Problems.typedProblem;
import static io.julienmetral.tasks.support.Problems.untypedProblem;
import static io.julienmetral.tasks.support.WebCallers.user;
import static io.julienmetral.tasks.support.WebCallers.withoutUid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebLayerTest
class AuthControllerWebMvcTests {

    private static final String AUTH = "/api/v1/auth";

    private static final String VALID_PASSWORD = "new-password-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Nested
    class Login {

        @Test
        void loginWithoutTokenReturnsTheIssuedTokens() throws Exception {
            LoginRequestDto dto = new LoginRequestDto("alice@example.com", "password123");
            when(authService.login(dto)).thenReturn(new AuthResponseDto(
                    "access", "Bearer", Instant.parse("2030-01-01T00:15:00Z"),
                    "refresh", Instant.parse("2030-01-31T00:00:00Z")
            ));

            login("{\"email\": \"alice@example.com\", \"password\": \"password123\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("access"))
                    .andExpect(jsonPath("$.refreshToken").value("refresh"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "{\"email\": \"not-an-email\", \"password\": \"password123\"}",
                "{\"email\": \"  \", \"password\": \"password123\"}",
                "{\"email\": \"alice@example.com\", \"password\": \"\"}",
                "{\"email\": \"alice@example.com\", \"password\": \"   \"}",
                "{}",
                "{\"email\": "
        })
        void loginRejectsInvalidPayload(String body) throws Exception {
            login(body).andExpect(status().isBadRequest());

            verifyNoInteractions(authService, rateLimiter);
        }

        @Test
        void loginWithInvalidEmailPointsToIt() throws Exception {
            login("{\"email\": \"not-an-email\", \"password\": \"password123\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].pointer").value("#/email"));

            verifyNoInteractions(authService, rateLimiter);
        }

        @Test
        void wrongCredentialsReturnAnUntypedUnauthorizedProblem() throws Exception {
            when(authService.login(any())).thenThrow(new InvalidCredentialsException());

            login("{\"email\": \"alice@example.com\", \"password\": \"wrong-password\"}")
                    .andExpect(untypedProblem(401, "Unauthorized"))
                    .andExpect(jsonPath("$.detail").value("Invalid email or password"))
                    .andExpect(jsonPath("$.message").doesNotExist());
        }

        @Test
        void loginOverTheRateLimitReturnsTooManyRequestsBeforeCheckingThePassword() throws Exception {
            doThrow(new RateLimitExceededException(Duration.ofMillis(1500)))
                    .when(rateLimiter).login(anyString(), anyString());

            login("{\"email\": \"alice@example.com\", \"password\": \"password123\"}")
                    .andExpect(untypedProblem(429, "Too Many Requests"))
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
                    .andExpect(jsonPath("$.detail").value("Too many requests, try again in 2 seconds"));

            verifyNoInteractions(authService);
        }

        private ResultActions login(String body) throws Exception {
            return postJson("/login", body);
        }
    }

    @Nested
    class RefreshAndLogout {

        @ParameterizedTest
        @ValueSource(strings = {
                "{}", "{\"refreshToken\": null}", "{\"refreshToken\": \"\"}", "{\"refreshToken\": \"   \"}"
        })
        void blankOrMissingRefreshTokenIsBadRequest(String body) throws Exception {
            postJson("/refresh", body).andExpect(status().isBadRequest());

            verifyNoInteractions(authService);
        }

        @Test
        void oversizedRefreshTokenIsBadRequest() throws Exception {
            postJson("/refresh", tokenBody("refreshToken", "a".repeat(129))).andExpect(status().isBadRequest());

            verifyNoInteractions(authService);
        }

        @Test
        void accessTokenUsedAsRefreshTokenIsRejected() throws Exception {
            // A JWT is longer than 128 characters, so validation rejects it before the service sees it
            postJson("/refresh", tokenBody("refreshToken", accessToken())).andExpect(status().isBadRequest());

            verifyNoInteractions(authService);
        }

        @Test
        void invalidRefreshTokenReturnsAnUntypedUnauthorizedProblem() throws Exception {
            when(authService.refresh("revoked")).thenThrow(new InvalidRefreshTokenException());

            postJson("/refresh", tokenBody("refreshToken", "revoked"))
                    .andExpect(untypedProblem(401, "Unauthorized"))
                    .andExpect(jsonPath("$.detail").value("The refresh token is invalid, expired or revoked"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"{}", "{\"refreshToken\": \"\"}", "{\"refreshToken\": \"  \"}"})
        void logoutWithBlankOrMissingTokenIsBadRequest(String body) throws Exception {
            postJson("/logout", body).andExpect(status().isBadRequest());

            verifyNoInteractions(authService);
        }

        @Test
        void logoutWithOversizedTokenIsBadRequest() throws Exception {
            postJson("/logout", tokenBody("refreshToken", "a".repeat(129))).andExpect(status().isBadRequest());

            verifyNoInteractions(authService);
        }

        @Test
        void logoutWithoutAccessTokenIsAccepted() throws Exception {
            postJson("/logout", tokenBody("refreshToken", "some-token")).andExpect(status().isNoContent());

            verify(authService).logout("some-token");
        }
    }

    @Nested
    class EmailVerification {

        @ParameterizedTest
        @ValueSource(strings = {"{\"token\": \"\"}", "{\"token\": \"   \"}", "{}", "{\"token\": null}"})
        void verifyWithBlankOrMissingTokenReturnsBadRequest(String body) throws Exception {
            postJson("/verify-email", body).andExpect(status().isBadRequest());

            verifyNoInteractions(emailVerificationService);
        }

        @Test
        void verifyWithOversizedTokenReturnsBadRequest() throws Exception {
            postJson("/verify-email", tokenBody("token", "a".repeat(129))).andExpect(status().isBadRequest());

            verifyNoInteractions(emailVerificationService);
        }

        @Test
        void invalidVerificationTokenReturnsInvalidTokenProblem() throws Exception {
            doThrow(new InvalidEmailVerificationTokenException()).when(emailVerificationService).verify("used");

            postJson("/verify-email", tokenBody("token", "used"))
                    .andExpect(typedProblem(400, "invalid-token", "Invalid or expired token"))
                    .andExpect(jsonPath("$.detail")
                            .value("The email verification token is invalid, expired or already used"));
        }

        @Test
        void resendWithoutTokenReturnsUnauthorized() throws Exception {
            mockMvc.perform(post(AUTH + "/verify-email/resend"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(emailVerificationService);
        }

        @Test
        void resendWithATokenWithoutUidIsForbidden() throws Exception {
            mockMvc.perform(post(AUTH + "/verify-email/resend").with(withoutUid()))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(emailVerificationService, rateLimiter);
        }

        @Test
        void resendWhenAlreadyVerifiedReturnsEmailAlreadyVerifiedProblem() throws Exception {
            UUID id = UUID.randomUUID();
            doThrow(new EmailAlreadyVerifiedException()).when(emailVerificationService).resend(id);

            mockMvc.perform(post(AUTH + "/verify-email/resend").with(user(id)))
                    .andExpect(typedProblem(409, "email-already-verified", "Email already verified"));
        }

        @Test
        void resendOverTheRateLimitReturnsTooManyRequestsBeforeSending() throws Exception {
            UUID id = UUID.randomUUID();
            doThrow(new RateLimitExceededException(Duration.ofMinutes(20))).when(rateLimiter).verificationResend(id);

            mockMvc.perform(post(AUTH + "/verify-email/resend").with(user(id)))
                    .andExpect(untypedProblem(429, "Too Many Requests"))
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1200"));

            verifyNoInteractions(emailVerificationService);
        }
    }

    @Nested
    class PasswordReset {

        @ParameterizedTest
        // @Email rejects surrounding spaces before the service could trim them
        @ValueSource(strings = {"not-an-email", "  someone@example.com ", ""})
        void requestWithInvalidEmailReturnsBadRequest(String email) throws Exception {
            postJson("/password-reset/request", tokenBody("email", email)).andExpect(status().isBadRequest());

            verifyNoInteractions(passwordResetService, rateLimiter);
        }

        @Test
        void requestWithoutEmailReturnsBadRequest() throws Exception {
            postJson("/password-reset/request", "{}").andExpect(status().isBadRequest());

            verifyNoInteractions(passwordResetService, rateLimiter);
        }

        @Test
        void requestIsAcceptedWithoutToken() throws Exception {
            postJson("/password-reset/request", tokenBody("email", "alice@example.com"))
                    .andExpect(status().isAccepted());

            verify(passwordResetService).request("alice@example.com");
        }

        @Test
        void requestOverTheRateLimitReturnsTooManyRequestsBeforeSending() throws Exception {
            doThrow(new RateLimitExceededException(Duration.ofHours(1)))
                    .when(rateLimiter).passwordResetRequest(anyString(), anyString());

            postJson("/password-reset/request", tokenBody("email", "alice@example.com"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3600"));

            verifyNoInteractions(passwordResetService);
        }

        @ParameterizedTest
        @ValueSource(strings = {"short77", "        "})
        void confirmWithTooShortOrBlankPasswordReturnsBadRequest(String newPassword) throws Exception {
            confirm("token", newPassword).andExpect(status().isBadRequest());

            verifyNoInteractions(passwordResetService);
        }

        @Test
        void confirmWithTooLongPasswordReturnsBadRequest() throws Exception {
            confirm("token", "x".repeat(129)).andExpect(status().isBadRequest());

            verifyNoInteractions(passwordResetService);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void confirmWithBlankTokenReturnsBadRequest(String token) throws Exception {
            confirm(token, VALID_PASSWORD).andExpect(status().isBadRequest());

            verifyNoInteractions(passwordResetService);
        }

        @Test
        void confirmWithOversizedTokenReturnsBadRequest() throws Exception {
            confirm("t".repeat(129), VALID_PASSWORD).andExpect(status().isBadRequest());

            verifyNoInteractions(passwordResetService);
        }

        @Test
        void confirmWithoutTokenReturnsBadRequest() throws Exception {
            postJson("/password-reset/confirm", tokenBody("newPassword", VALID_PASSWORD))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(passwordResetService);
        }

        @Test
        void confirmWithMinimumPasswordLengthReachesTheService() throws Exception {
            confirm("token", "12345678").andExpect(status().isNoContent());

            verify(passwordResetService).confirm("token", "12345678");
        }

        @Test
        void invalidResetTokenReturnsInvalidTokenProblem() throws Exception {
            doThrow(new InvalidPasswordResetTokenException())
                    .when(passwordResetService).confirm("used", VALID_PASSWORD);

            confirm("used", VALID_PASSWORD)
                    .andExpect(typedProblem(400, "invalid-token", "Invalid or expired token"))
                    .andExpect(jsonPath("$.detail")
                            .value("The password reset token is invalid, expired or already used"));
        }

        @Test
        void confirmOverTheRateLimitReturnsTooManyRequestsBeforeCheckingTheToken() throws Exception {
            doThrow(new RateLimitExceededException(Duration.ofSeconds(30)))
                    .when(rateLimiter).passwordResetConfirm(anyString());

            confirm("token", VALID_PASSWORD)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"));

            verifyNoInteractions(passwordResetService);
        }

        private ResultActions confirm(String token, String newPassword) throws Exception {
            return postJson(
                    "/password-reset/confirm",
                    "{\"token\": \"%s\", \"newPassword\": \"%s\"}".formatted(token, newPassword)
            );
        }
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(AUTH + path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String tokenBody(String field, String value) {
        return "{\"%s\": \"%s\"}".formatted(field, value);
    }

    private String accessToken() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("tasks-api")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim("uid", UUID.randomUUID().toString())
                .claim("roles", List.of("ROLE_USER"))
                .build();

        return jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
