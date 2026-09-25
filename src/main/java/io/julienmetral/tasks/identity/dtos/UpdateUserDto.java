package io.julienmetral.tasks.identity.dtos;

import jakarta.validation.constraints.Size;

public record UpdateUserDto(

        @Size(max = 255)
        String displayName
) {
}
