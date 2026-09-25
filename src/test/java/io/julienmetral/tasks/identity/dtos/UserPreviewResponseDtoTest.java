package io.julienmetral.tasks.identity.dtos;

import io.julienmetral.tasks.identity.entities.User;
import io.julienmetral.tasks.identity.entities.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserPreviewResponseDtoTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static User user(boolean enabled, Instant emailVerifiedAt) {
        User user = new User();
        user.setId(USER_ID);
        user.setDisplayName("Alice");
        user.setEnabled(enabled);
        user.setEmailVerifiedAt(emailVerifiedAt);
        return user;
    }

    @Test
    void loadedActiveUserExposesItsIdNameAndStatus() {
        UserPreviewResponseDto preview = UserPreviewResponseDto.of(
                user(true, Instant.parse("2026-01-01T00:00:00Z")), USER_ID);

        assertThat(preview).isEqualTo(new UserPreviewResponseDto(USER_ID, "Alice", UserStatus.ACTIVE));
    }

    @Test
    void loadedUserStatusReflectsTheAccountState() {
        assertThat(UserPreviewResponseDto.of(user(true, null), USER_ID).status())
                .isEqualTo(UserStatus.UNVERIFIED);
        assertThat(UserPreviewResponseDto.of(user(false, null), USER_ID).status())
                .isEqualTo(UserStatus.DISABLED);
    }

    @Test
    void loadedUserWinsOverTheIdColumn() {
        UserPreviewResponseDto preview = UserPreviewResponseDto.of(user(true, null), OTHER_ID);

        assertThat(preview.id()).isEqualTo(USER_ID);
    }

    @Test
    void loadedUserWithoutIdColumnStillProducesAPreview() {
        UserPreviewResponseDto preview = UserPreviewResponseDto.of(user(true, null), null);

        assertThat(preview.id()).isEqualTo(USER_ID);
        assertThat(preview.displayName()).isEqualTo("Alice");
    }

    @Test
    void missingUserWithIdIsShownAsDeletedWithoutName() {
        UserPreviewResponseDto preview = UserPreviewResponseDto.of(null, USER_ID);

        assertThat(preview).isEqualTo(new UserPreviewResponseDto(USER_ID, null, UserStatus.DELETED));
    }

    @Test
    void noUserAndNoIdGivesNull() {
        assertThat(UserPreviewResponseDto.of(null, null)).isNull();
    }
}
