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
    private static final String IDENTICON_URL = "http://localhost/api/v1/identicons/" + USER_ID;

    private final MediaUrls mediaUrls = mock(MediaUrls.class);

    private static User user() {
        User user = new User();
        user.setId(USER_ID);
        user.getProfile().setId(USER_ID);
        user.setEmail("alice@example.com");
        user.setDisplayName("Alice");
        user.setEmailVerifiedAt(VERIFIED_AT);
        user.setLastLoginAt(LAST_LOGIN_AT);
        user.setRoles(Set.of(UserRole.USER));
        return user;
    }

    @Test
    void userExposesItsAccountAndTheAvatarUrlOfItsProfile() {
        User user = user();
        user.getProfile().setAvatar(new Media());
        when(mediaUrls.avatarOf(user.getProfile())).thenReturn(AVATAR_URL);

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
    void userWithoutAvatarGetsTheUrlMediaUrlsGivesForItsProfile() {
        User user = user();
        when(mediaUrls.avatarOf(user.getProfile())).thenReturn(IDENTICON_URL);

        UserResponseDto dto = new UserResponseDto(user, mediaUrls);

        assertThat(dto.avatarUrl()).isEqualTo(IDENTICON_URL);
        assertThat(dto.avatarPending()).isFalse();
    }

    @Test
    void userWithPendingUploadIsMarkedPendingAndKeepsItsCurrentAvatarUrl() {
        User user = user();
        user.getProfile().setAvatar(new Media());
        user.getProfile().setPendingAvatar(new Media());
        when(mediaUrls.avatarOf(user.getProfile())).thenReturn(AVATAR_URL);

        UserResponseDto dto = new UserResponseDto(user, mediaUrls);

        assertThat(dto.avatarPending()).isTrue();
        assertThat(dto.avatarUrl()).isEqualTo(AVATAR_URL);
    }

    @Test
    void firstPendingUploadIsMarkedPending() {
        User user = user();
        user.getProfile().setPendingAvatar(new Media());
        when(mediaUrls.avatarOf(user.getProfile())).thenReturn(IDENTICON_URL);

        UserResponseDto dto = new UserResponseDto(user, mediaUrls);

        assertThat(dto.avatarPending()).isTrue();
        assertThat(dto.avatarUrl()).isEqualTo(IDENTICON_URL);
    }

    @Test
    void processedAvatarIsNotPending() {
        User user = user();
        user.getProfile().setAvatar(new Media());

        assertThat(new UserResponseDto(user, mediaUrls).avatarPending()).isFalse();
    }
}
