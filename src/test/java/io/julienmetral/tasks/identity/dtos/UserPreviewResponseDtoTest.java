package io.julienmetral.tasks.identity.dtos;

import io.julienmetral.tasks.identity.entities.UserStatus;
import io.julienmetral.tasks.identity.entities.UserSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.julienmetral.tasks.support.UserSummaries.summary;
import static org.assertj.core.api.Assertions.assertThat;

class UserPreviewResponseDtoTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-02-01T00:00:00Z");

    private static UserSummary user(boolean enabled, Instant emailVerifiedAt, Instant deletedAt) {
        return summary(USER_ID, "Alice", enabled, emailVerifiedAt, deletedAt);
    }

    @Test
    void activeUserExposesItsIdNameAndStatus() {
        UserPreviewResponseDto preview = UserPreviewResponseDto.of(user(true, VERIFIED_AT, null));

        assertThat(preview).isEqualTo(new UserPreviewResponseDto(USER_ID, "Alice", UserStatus.ACTIVE));
    }

    @Test
    void statusReflectsTheAccountState() {
        assertThat(UserPreviewResponseDto.of(user(true, null, null)).status()).isEqualTo(UserStatus.UNVERIFIED);
        assertThat(UserPreviewResponseDto.of(user(false, VERIFIED_AT, null)).status()).isEqualTo(UserStatus.DISABLED);
    }

    @Test
    void softDeletedUserKeepsItsNameAndIsShownAsDeleted() {
        UserPreviewResponseDto preview = UserPreviewResponseDto.of(user(true, VERIFIED_AT, DELETED_AT));

        assertThat(preview).isEqualTo(new UserPreviewResponseDto(USER_ID, "Alice", UserStatus.DELETED));
    }

    @Test
    void noUserGivesNull() {
        assertThat(UserPreviewResponseDto.of(null)).isNull();
    }
}
