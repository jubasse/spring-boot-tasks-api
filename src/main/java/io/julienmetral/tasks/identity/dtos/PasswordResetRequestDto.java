package io.julienmetral.tasks.identity.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequestDto(
        @NotBlank
        @Email
        @Size(max = 320)
        String email
) {
}
