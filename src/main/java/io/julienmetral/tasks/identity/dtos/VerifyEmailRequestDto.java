package io.julienmetral.tasks.identity.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyEmailRequestDto(
        @NotBlank
        @Size(max = 128)
        String token
) {
}
