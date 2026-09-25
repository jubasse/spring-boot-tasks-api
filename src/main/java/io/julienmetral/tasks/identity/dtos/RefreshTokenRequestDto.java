package io.julienmetral.tasks.identity.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshTokenRequestDto(
        @NotBlank
        @Size(max = 128)
        String refreshToken
) {
}
