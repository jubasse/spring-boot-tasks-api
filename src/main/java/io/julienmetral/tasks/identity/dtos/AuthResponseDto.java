package io.julienmetral.tasks.identity.dtos;

import java.time.Instant;

public record AuthResponseDto(
        String accessToken,
        String tokenType,
        Instant expiresAt
) {}
