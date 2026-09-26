package io.julienmetral.tasks.identity.dtos;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.entities.UserSummary;
import io.julienmetral.tasks.media.model.Media;
import io.julienmetral.tasks.media.services.MediaUrls;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserSummaries.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserPreviewResponseDtoTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-02-01T00:00:00Z");
    private static final String AVATAR_URL = "https://storage.example/avatar/key?signature=abc";

    private final MediaUrls mediaUrls = mock(MediaUrls.class);

    private static UserSummary user(boolean enabled, Instant emailVerifiedAt, Instant deletedAt) {
        return summary(USER_ID, "Alice", enabled, emailVerifiedAt, deletedAt);
    }

    private static UserSummary withAvatar(UserSummary user, Media avatar) {
        ReflectionTestUtils.setField(user, "avatar", avatar);
        return user;
    }

    @Test
    void activeUserExposesItsIdNameAndStatus() {
        UserPreviewResponseDto preview = UserPreviewResponseDto.of(user(true, VERIFIED_AT, null), mediaUrls);

        assertThat(preview).isEqualTo(new UserPreviewResponseDto(USER_ID, "Alice", UserStatus.ACTIVE, null));
    }

    @Test
    void statusReflectsTheAccountState() {
        assertThat(UserPreviewResponseDto.of(user(true, null, null), mediaUrls).status())
                .isEqualTo(UserStatus.UNVERIFIED);
        assertThat(UserPreviewResponseDto.of(user(false, VERIFIED_AT, null), mediaUrls).status())
                .isEqualTo(UserStatus.DISABLED);
    }

    @Test
    void softDeletedUserKeepsItsNameAndIsShownAsDeleted() {
        UserPreviewResponseDto preview = UserPreviewResponseDto.of(user(true, VERIFIED_AT, DELETED_AT), mediaUrls);

        assertThat(preview).isEqualTo(new UserPreviewResponseDto(USER_ID, "Alice", UserStatus.DELETED, null));
    }

    @Test
    void userWithAvatarExposesItsPresignedUrl() {
        Media avatar = new Media();
        when(mediaUrls.of(avatar)).thenReturn(AVATAR_URL);

        UserPreviewResponseDto preview = UserPreviewResponseDto.of(
                withAvatar(user(true, VERIFIED_AT, null), avatar), mediaUrls);

        assertThat(preview.avatarUrl()).isEqualTo(AVATAR_URL);
    }

    @Test
    void softDeletedUserKeepsItsAvatarUrl() {
        Media avatar = new Media();
        when(mediaUrls.of(avatar)).thenReturn(AVATAR_URL);

        UserPreviewResponseDto preview = UserPreviewResponseDto.of(
                withAvatar(user(true, VERIFIED_AT, DELETED_AT), avatar), mediaUrls);

        assertThat(preview).isEqualTo(new UserPreviewResponseDto(USER_ID, "Alice", UserStatus.DELETED, AVATAR_URL));
    }

    @Test
    void userWithoutAvatarHasNoAvatarUrl() {
        UserPreviewResponseDto preview = UserPreviewResponseDto.of(user(true, VERIFIED_AT, null), mediaUrls);

        assertThat(preview.avatarUrl()).isNull();
    }

    @Test
    void noUserGivesNullWithoutSigningAnything() {
        assertThat(UserPreviewResponseDto.of(null, mediaUrls)).isNull();

        verifyNoInteractions(mediaUrls);
    }
}
