package io.julienmetral.tasks.identity.dtos;

import io.julienmetral.tasks.identity.entities.UserProfile;
import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.media.services.MediaUrls;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static io.julienmetral.tasks.support.UserProfiles.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserProfileResponseDtoTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String AVATAR_URL = "https://storage.example/avatar/key?signature=abc";

    private final MediaUrls mediaUrls = mock(MediaUrls.class);

    @Test
    void activeUserExposesItsIdNameStatusAndAvatarUrl() {
        UserProfile profile = profile(USER_ID, "Alice", UserStatus.ACTIVE);
        when(mediaUrls.avatarOf(profile)).thenReturn(AVATAR_URL);

        UserProfileResponseDto dto = UserProfileResponseDto.of(profile, mediaUrls);

        assertThat(dto).isEqualTo(new UserProfileResponseDto(USER_ID, "Alice", UserStatus.ACTIVE, AVATAR_URL));
    }

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    void statusIsTheOneCopiedOnTheProfile(UserStatus status) {
        UserProfileResponseDto dto = UserProfileResponseDto.of(profile(USER_ID, "Alice", status), mediaUrls);

        assertThat(dto.status()).isEqualTo(status);
    }

    @Test
    void deletedUserKeepsItsName() {
        UserProfile profile = profile(USER_ID, "Alice", UserStatus.DELETED);

        UserProfileResponseDto dto = UserProfileResponseDto.of(profile, mediaUrls);

        assertThat(dto.displayName()).isEqualTo("Alice");
        assertThat(dto.status()).isEqualTo(UserStatus.DELETED);
    }

    @Test
    void avatarUrlComesFromMediaUrlsAvatarOf() {
        UserProfile profile = profile(USER_ID, "Alice", UserStatus.DISABLED);
        String identiconUrl = "/api/v1/identicons/" + USER_ID;
        when(mediaUrls.avatarOf(profile)).thenReturn(identiconUrl);

        assertThat(UserProfileResponseDto.of(profile, mediaUrls).avatarUrl()).isEqualTo(identiconUrl);
    }

    @Test
    void noUserGivesNullWithoutSigningAnything() {
        assertThat(UserProfileResponseDto.of(null, mediaUrls)).isNull();

        verifyNoInteractions(mediaUrls);
    }
}
