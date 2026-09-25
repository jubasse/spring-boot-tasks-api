package io.julienmetral.tasks.identity.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmDto(
        @NotBlank
        @Size(max = 128)
        String token,

        // Same rules as CreateUserDto.password
        @NotBlank
        @Size(min = 8, max = 128)
        String newPassword
) {
}
