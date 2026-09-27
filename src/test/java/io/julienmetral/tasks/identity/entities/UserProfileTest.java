package io.julienmetral.tasks.identity.entities;

import io.julienmetral.tasks.media.model.Media;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class UserProfileTest {

    private static UserProfile profileWithAvatar(UserStatus status, Media avatar) {
        UserProfile profile = new UserProfile();
        profile.setStatus(status);
        profile.setAvatar(avatar);
        return profile;
    }

    @Test
    void newProfileIsUnverified() {
        assertThat(new UserProfile().getStatus()).isEqualTo(UserStatus.UNVERIFIED);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ACTIVE", "UNVERIFIED"})
    void photoIsVisibleWhileTheAccountIsEnabled(UserStatus status) {
        Media avatar = new Media();

        assertThat(profileWithAvatar(status, avatar).visibleAvatar()).isSameAs(avatar);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"DISABLED", "DELETED"})
    void photoIsHiddenWhileTheAccountIsDisabledOrDeleted(UserStatus status) {
        UserProfile profile = profileWithAvatar(status, new Media());

        assertThat(profile.visibleAvatar()).isNull();
    }

    @Test
    void hidingThePhotoOfADisabledAccountKeepsIt() {
        Media avatar = new Media();
        UserProfile profile = profileWithAvatar(UserStatus.DISABLED, avatar);

        profile.visibleAvatar();

        assertThat(profile.getAvatar()).isSameAs(avatar);
    }

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    void profileWithoutPhotoHasNoVisiblePhoto(UserStatus status) {
        assertThat(profileWithAvatar(status, null).visibleAvatar()).isNull();
    }

    @Test
    void pendingUploadIsNeverTheVisiblePhoto() {
        UserProfile profile = profileWithAvatar(UserStatus.ACTIVE, null);
        profile.setPendingAvatar(new Media());

        assertThat(profile.visibleAvatar()).isNull();
    }
}
