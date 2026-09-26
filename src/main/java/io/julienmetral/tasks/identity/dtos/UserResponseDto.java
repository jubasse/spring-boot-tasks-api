package io.julienmetral.tasks.identity.dtos;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.media.services.MediaUrls;
import io.julienmetral.tasks.identity.entities.UserRole;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UserResponseDto(
        UUID id,
        String email,
        String displayName,
        boolean enabled,
        Instant emailVerifiedAt,
        Instant lastLoginAt,
        Set<UserRole> roles,
        Instant createdAt,
        Instant updatedAt,
        String avatarUrl
) {

    public UserResponseDto(User user, MediaUrls mediaUrls) {
        this(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.isEnabled(),
                user.getEmailVerifiedAt(),
                user.getLastLoginAt(),
                Set.copyOf(user.getRoles()),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                mediaUrls.of(user.getAvatar())
        );
    }
}
