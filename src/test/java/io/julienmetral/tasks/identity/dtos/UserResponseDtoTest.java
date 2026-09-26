package io.julienmetral.tasks.identity.dtos;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserRole;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.services.MediaUrls;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserResponseDtoTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LAST_LOGIN_AT = Instant.parse("2026-01-02T00:00:00Z");
    private static final String AVATAR_URL = "https://storage.example/avatar/key?signature=abc";

    private final MediaUrls mediaUrls = mock(MediaUrls.class);

    private static User user() {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("alice@example.com");
        user.setDisplayName("Alice");
        user.setEmailVerifiedAt(VERIFIED_AT);
        user.setLastLoginAt(LAST_LOGIN_AT);
        user.setRoles(Set.of(UserRole.USER));
        return user;
    }

    @Test
    void userWithAvatarExposesItsPresignedUrl() {
        User user = user();
        Media avatar = new Media();
        user.setAvatar(avatar);
        when(mediaUrls.of(avatar)).thenReturn(AVATAR_URL);

        UserResponseDto dto = new UserResponseDto(user, mediaUrls);

        assertThat(dto.avatarUrl()).isEqualTo(AVATAR_URL);
        assertThat(dto.id()).isEqualTo(USER_ID);
        assertThat(dto.email()).isEqualTo("alice@example.com");
        assertThat(dto.displayName()).isEqualTo("Alice");
        assertThat(dto.enabled()).isTrue();
        assertThat(dto.emailVerifiedAt()).isEqualTo(VERIFIED_AT);
        assertThat(dto.lastLoginAt()).isEqualTo(LAST_LOGIN_AT);
        assertThat(dto.roles()).containsExactly(UserRole.USER);
    }

    @Test
    void userWithoutAvatarHasNoAvatarUrl() {
        UserResponseDto dto = new UserResponseDto(user(), mediaUrls);

        assertThat(dto.avatarUrl()).isNull();
    }
}
