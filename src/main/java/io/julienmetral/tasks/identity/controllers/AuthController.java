package io.julienmetral.tasks.identity.controllers;

import io.julienmetral.tasks.identity.dtos.AuthResponseDto;
import io.julienmetral.tasks.identity.dtos.LoginRequestDto;
import io.julienmetral.tasks.identity.dtos.PasswordResetConfirmDto;
import io.julienmetral.tasks.identity.dtos.PasswordResetRequestDto;
import io.julienmetral.tasks.identity.dtos.RefreshTokenRequestDto;
import io.julienmetral.tasks.identity.dtos.VerifyEmailRequestDto;
import io.julienmetral.tasks.identity.security.CurrentUser;
import io.julienmetral.tasks.identity.services.AuthService;
import io.julienmetral.tasks.identity.services.EmailVerificationService;
import io.julienmetral.tasks.identity.services.PasswordResetService;
import io.julienmetral.tasks.ratelimit.services.RateLimiter;
import io.julienmetral.tasks.shared.exceptions.ProblemType;
import io.julienmetral.tasks.shared.openapi.DocumentedProblems;
import io.julienmetral.tasks.shared.openapi.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Sign in, keep a session going, verify an email and reset a password.")
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;

    @Operation(summary = "Sign in")
    @RateLimited
    @ApiResponse(responseCode = "401", ref = "#/components/responses/InvalidCredentials")
    @PostMapping("/login")
    public ResponseEntity<AuthResponseDto> login(
            @Valid @RequestBody LoginRequestDto dto,
            HttpServletRequest request
    ) {
        rateLimiter.login(request.getRemoteAddr(), dto.email());

        return ResponseEntity.ok(
                authService.login(dto)
        );
    }

    @Operation(summary = "Trade a refresh token for a new token pair")
    @ApiResponse(responseCode = "401", ref = "#/components/responses/InvalidCredentials")
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponseDto> refreshTokens(
            @Valid @RequestBody RefreshTokenRequestDto dto
    ) {
        return ResponseEntity.ok(
                authService.refresh(dto.refreshToken())
        );
    }

    @Operation(summary = "Sign out: revoke the refresh token and its family")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Valid @RequestBody RefreshTokenRequestDto dto
    ) {
        authService.logout(dto.refreshToken());

        return ResponseEntity
                .noContent()
                .build();
    }

    @Operation(summary = "Verify an email with the token of its link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DocumentedProblems(ProblemType.INVALID_TOKEN)
    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(
            @Valid @RequestBody VerifyEmailRequestDto dto
    ) {
        emailVerificationService.verify(dto.token());

        return ResponseEntity
                .noContent()
                .build();
    }

    @Operation(summary = "Send the verification email again")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DocumentedProblems(ProblemType.EMAIL_ALREADY_VERIFIED)
    @RateLimited
    @PostMapping("/verify-email/resend")
    public ResponseEntity<Void> resendVerificationEmail() {
        var userId = currentUser
                .getId()
                .orElseThrow(() -> new AccessDeniedException("Token has no user id"));

        rateLimiter.verificationResend(userId);

        emailVerificationService.resend(userId);

        return ResponseEntity
                .noContent()
                .build();
    }

    // Always 202, whether or not the email belongs to an account
    @Operation(summary = "Ask for a password reset email; always accepted, whether the account exists or not")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RateLimited
    @PostMapping("/password-reset/request")
    public ResponseEntity<Void> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequestDto dto,
            HttpServletRequest request
    ) {
        rateLimiter.passwordResetRequest(request.getRemoteAddr(), dto.email());

        passwordResetService.request(dto.email());

        return ResponseEntity
                .accepted()
                .build();
    }

    @Operation(summary = "Set a new password with the token of the reset email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DocumentedProblems(ProblemType.INVALID_TOKEN)
    @RateLimited
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmDto dto,
            HttpServletRequest request
    ) {
        rateLimiter.passwordResetConfirm(request.getRemoteAddr());

        passwordResetService.confirm(dto.token(), dto.newPassword());

        return ResponseEntity
                .noContent()
                .build();
    }
}
