package io.julienmetral.tasks.identity.dtos;

import io.julienmetral.tasks.identity.entities.User;

import java.util.UUID;

public record UserPreviewResponseDto(
        UUID id,
        String displayName
) {
    public UserPreviewResponseDto(User user) {
        this(
                user.getId(),
                user.getDisplayName()
        );
    }
}
