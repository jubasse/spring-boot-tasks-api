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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;

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

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponseDto> refresh(
            @Valid @RequestBody RefreshTokenRequestDto dto
    ) {
        return ResponseEntity.ok(
                authService.refresh(dto.refreshToken())
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Valid @RequestBody RefreshTokenRequestDto dto
    ) {
        authService.logout(dto.refreshToken());

        return ResponseEntity
                .noContent()
                .build();
    }

    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(
            @Valid @RequestBody VerifyEmailRequestDto dto
    ) {
        emailVerificationService.verify(dto.token());

        return ResponseEntity
                .noContent()
                .build();
    }

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
